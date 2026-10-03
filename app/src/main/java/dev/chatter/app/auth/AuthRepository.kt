package dev.chatter.app.auth

import android.util.Log
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chatter.app.BuildConfig
import dev.chatter.app.net.HelixApi
import dev.chatter.app.net.HttpException
import dev.chatter.app.net.fetch
import dev.chatter.app.net.postForm
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID

data class Account(
    val login: String,
    val userId: String,
    val token: String,
    val refreshToken: String?,
    /** Epoch millis when [token] expires. */
    val expiresAt: Long,
    /** Display name and avatar, for the account switcher. */
    val displayName: String = "",
    val avatarUrl: String = "",
    /** What [token] may do; null for accounts stored before scopes were kept. */
    val scopes: Set<String>? = null,
) {
    /** The display name, or the login if Twitch has none. */
    val name: String get() = displayName.ifEmpty { login }

    /** What Chatter uses that this login was not given; see [TwitchScopes.missing]. */
    val missingScopes: Set<String> get() = TwitchScopes.missing(scopes)

    /**
     * Leaves out the tokens, so an account printed into a log or a crash report gives nothing away.
     */
    override fun toString(): String =
        "Account(login=$login, userId=$userId, token=$REDACTED, refreshToken=${refreshToken?.let { REDACTED }}, " +
            "expiresAt=$expiresAt, displayName=$displayName, scopes=$scopes)"

    private companion object {
        const val REDACTED = "‹redacted›"
    }
}

sealed interface AuthState {
    data object Loading : AuthState
    data object LoggedOut : AuthState

    /**
     * Reading without an account. Twitch lets anyone read chat; writing, whispers and everything
     * else in Helix need a login.
     */
    data object Guest : AuthState
    data class LoggedIn(val account: Account) : AuthState
}

/**
 * Twitch login through the implicit OAuth flow in a WebView: Twitch redirects to
 * `http://localhost#access_token=...` and the WebView catches it. These tokens cannot be refreshed;
 * once Twitch rejects one, the user logs in again. Accounts from the old device code login still
 * have a refresh token, which [refresh] renews.
 *
 * Several accounts can be logged in. [state] is the active one, the others wait in [accounts].
 */
class AuthRepository(
    private val store: DataStore<Preferences>,
    private val http: OkHttpClient,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    /** Every logged-in account, the active one included, in the order they were added. */
    val accounts: StateFlow<List<Account>> = _accounts

    /**
     * Accounts the keystore would not decrypt at start. They are written back unchanged by
     * [persist] and tried again on the next start.
     */
    private var unreadable: List<StoredAccount> = emptyList()

    private val _knownUserIds = MutableStateFlow<List<String>>(emptyList())
    /**
     * Every account on the phone, the [unreadable] ones included. Inbox and whispers are kept for
     * these.
     */
    val knownUserIds: StateFlow<List<String>> = _knownUserIds

    private val _sessionExpired = MutableStateFlow(false)
    /**
     * True when Twitch ended the session (expired or revoked token) rather than the user logging
     * out, so the login screen can say why it is back.
     */
    val sessionExpired: StateFlow<Boolean> = _sessionExpired

    /** Set by AppContainer; Helix and Auth need each other. */
    lateinit var helix: HelixApi

    private val refreshLock = Mutex()

    /** Held for every change to the account list. */
    private val writeLock = Mutex()

    private var activeId: String? = null

    val account: Account? get() = (state.value as? AuthState.LoggedIn)?.account
    val token: String? get() = account?.token

    /** Whether there is a chat to read: an account is logged in, or a guest is reading. */
    val canRead: Boolean get() = state.value.let { it is AuthState.LoggedIn || it is AuthState.Guest }

    /**
     * Reading as a guest while no account is logged in. Logging in ends it, so logging that account
     * out leads to the login screen and not back into the chat.
     */
    private var guest = false

    /** Loads the stored accounts and refreshes tokens where needed. Call once at start. */
    suspend fun restore() {
        val p = store.data.first()
        val stored = AccountStore.decode(p[ACCOUNTS_KEY])
        // Older versions stored a single account in keys of its own. It becomes the first list
        // entry; the old keys are removed on the next write.
        val migrated = stored.isEmpty()
        val (accounts, unread) = decryptAll(if (migrated) listOfNotNull(legacyAccount(p)) else stored)
        writeLock.withLock {
            _accounts.value = accounts
            unreadable = unread
            activeId = AccountStore.activeIn(accounts.map { it.userId }, p[ACTIVE_KEY])
            guest = p[GUEST_KEY] == true
            // Optimistically, so the chat can connect right away, also offline.
            publish()
            if (migrated && accounts.isNotEmpty()) persist()
        }
        // Tokens from a logout that happened offline.
        revokePending()
        if (activeId == null) return
        freshToken()
        refreshProfiles()
        learnScopes()
    }

    /**
     * Asks Twitch for the scopes of accounts stored before scopes were kept, so the account page
     * can point out missing ones. Until then such an account may try every command.
     */
    private suspend fun learnScopes() {
        for (acc in _accounts.value.filter { it.scopes == null }) {
            val granted = runCatching { helix.validate(acc.token).scopes.toSet() }.getOrNull() ?: continue
            writeLock.withLock {
                val at = _accounts.value.indexOfFirst { it.userId == acc.userId && it.token == acc.token }
                if (at < 0) return@withLock
                _accounts.value = _accounts.value.toMutableList().also { it[at] = it[at].copy(scopes = granted) }
                persist()
                publish()
            }
        }
    }

    /** See [AccountStore.readAll]. */
    private suspend fun decryptAll(stored: List<StoredAccount>): Pair<List<Account>, List<StoredAccount>> {
        val read = AccountStore.readAll(stored, wait = { attempt -> delay(DECRYPT_RETRY_MS * attempt) }) { it.decrypted() }
        if (read.unreadable.isNotEmpty()) Log.w(TAG, "Keeping ${read.unreadable.size} account(s) unread until the keystore answers")
        return read.readable to read.unreadable
    }

    /** The one account an older version of the app stored, or null if there was none. */
    private fun legacyAccount(p: Preferences): StoredAccount? {
        val token = p[TOKEN_KEY] ?: return null
        val login = p[LOGIN_KEY] ?: return null
        val userId = p[USER_ID_KEY] ?: return null
        return StoredAccount(login, userId, token, p[REFRESH_KEY], p[EXPIRES_KEY] ?: 0L)
    }

    // ---- WebView login (implicit flow) -----------------------------------------------------

    /** Expected `state` parameter of the running login, to reject forged redirects. */
    private var pendingState: String? = null

    /**
     * [forceVerify] makes Twitch ask who is logging in even if its session already knows. Needed
     * for adding a second account; otherwise Twitch returns a token for the account already logged
     * in.
     */
    fun authorizeUrl(forceVerify: Boolean = false): String {
        val state = UUID.randomUUID().toString()
        pendingState = state
        return "https://id.twitch.tv/oauth2/authorize".toUri().buildUpon()
            .appendQueryParameter("response_type", "token")
            .appendQueryParameter("client_id", BuildConfig.TWITCH_CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", TwitchScopes.ALL.joinToString(" "))
            .appendQueryParameter("state", state)
            .apply { if (forceVerify) appendQueryParameter("force_verify", "true") }
            .build().toString()
    }

    /**
     * Handles the redirect `http://localhost#access_token=...&state=...`. Returns null if [url] is
     * not our redirect, otherwise whether the login succeeded.
     */
    suspend fun handleRedirect(url: String): Result<Unit>? {
        if (!LoginUrls.isRedirect(url)) return null
        // A state is valid for one answer, and only while a login is running.
        val expected = pendingState
        pendingState = null
        val params = LoginUrls.answer(url)
        params["error"]?.let { return Result.failure(IllegalStateException(params["error_description"] ?: it)) }
        if (expected == null || params["state"] != expected) return Result.failure(IllegalStateException("State mismatch"))
        val token = params["access_token"] ?: return Result.failure(IllegalStateException("No token"))
        val result = runCatching {
            val v = helix.validate(token)
            // expires_in == 0 means the token does not expire on its own.
            val expiresAt = if (v.expiresIn > 0) System.currentTimeMillis() + v.expiresIn * 1000L else Long.MAX_VALUE
            saveAccount(
                Account(v.login, v.userId, token, refreshToken = null, expiresAt = expiresAt, scopes = v.scopes.toSet()),
                revokeReplaced = true,
            )
        }
        // Afterwards, so a failed picture download cannot fail the login.
        if (result.isSuccess) refreshProfiles()
        return result
    }

    // ---- Reading as a guest -------------------------------------------------------------------

    /** Reads chats without an account until one is added; see [AuthState.Guest]. */
    suspend fun continueAsGuest() = setGuest(true)

    /** Back to the login screen. The channels stay. */
    suspend fun leaveGuest() = setGuest(false)

    private suspend fun setGuest(on: Boolean) {
        writeLock.withLock {
            guest = on
            _sessionExpired.value = false
            persist()
            publish()
        }
    }

    // ---- Several accounts ---------------------------------------------------------------------

    /** Makes [userId] the active account. Does nothing for an unknown one. */
    suspend fun switchTo(userId: String) {
        writeLock.withLock {
            if (userId == activeId || _accounts.value.none { it.userId == userId }) return
            activeId = userId
            _sessionExpired.value = false
            persist()
            publish()
        }
        freshToken()
        refreshProfiles()
    }

    /** Logs one account out. If it was the active one, the next account takes over. */
    suspend fun remove(userId: String) = forget(userId, expired = false)

    /** [expired] when Twitch ended the session, not the user; see [sessionExpired]. */
    suspend fun logout(expired: Boolean = false) {
        forget(activeId ?: return, expired)
    }

    /**
     * Removes the account from the phone and, unless Twitch ended the session, revokes its tokens.
     * WebView tokens stay valid for months otherwise.
     */
    private suspend fun forget(userId: String, expired: Boolean) {
        val gone = writeLock.withLock {
            val gone = _accounts.value.firstOrNull { it.userId == userId } ?: return
            val rest = _accounts.value - gone
            _accounts.value = rest
            if (activeId == userId) activeId = rest.firstOrNull()?.userId
            // With another account left the login screen never shows, so there is nothing to
            // explain.
            _sessionExpired.value = expired && rest.isEmpty()
            persist()
            publish()
            gone
        }
        // Twitch's WebView session would log the next person straight back in. It cannot be told
        // which account it belongs to, so it is cleared on every logout.
        WebSession.clear()
        if (expired) return
        queueRevoke(listOfNotNull(gone.token, gone.refreshToken))
        revokePending()
    }

    // ---- Revoking tokens --------------------------------------------------------------------

    private val revokeLock = Mutex()

    /**
     * Queues [tokens] for revoking, encrypted. Stored before the first attempt, so an offline
     * logout or a killed process still revokes them on the next start.
     */
    private suspend fun queueRevoke(tokens: List<String>) {
        if (tokens.isEmpty()) return
        store.edit { p ->
            p[REVOKE_KEY] = AccountStore.encodeTokens(AccountStore.decodeTokens(p[REVOKE_KEY]) + tokens.map(TokenCipher::encrypt))
        }
    }

    /** Revokes what Twitch can be reached for and keeps the rest queued. */
    suspend fun revokePending() = revokeLock.withLock {
        val queued = AccountStore.decodeTokens(store.data.first()[REVOKE_KEY])
        if (queued.isEmpty()) return@withLock
        // A token that cannot be decrypted cannot be revoked either. One the keystore did not
        // answer for stays queued.
        val left = queued.filter { encrypted ->
            when (val t = TokenCipher.open(encrypted)) {
                is Decrypted.Plain -> !revoke(t.text)
                Decrypted.Lost -> false
                Decrypted.Unavailable -> true
            }
        }
        store.edit { p ->
            // Tokens queued while this ran stay queued.
            val now = AccountStore.decodeTokens(p[REVOKE_KEY]) - queued.toSet() + left
            if (now.isEmpty()) p.remove(REVOKE_KEY) else p[REVOKE_KEY] = AccountStore.encodeTokens(now)
        }
    }

    /**
     * Revokes [token] at Twitch. True when it is gone (revoked, or already invalid: Twitch answers
     * 400), false when it should be tried again.
     */
    private suspend fun revoke(token: String): Boolean = try {
        val body = FormBody.Builder()
            .add("client_id", BuildConfig.TWITCH_CLIENT_ID)
            .add("token", token)
            .build()
        http.fetch(Request.Builder().url("https://id.twitch.tv/oauth2/revoke").post(body).build())
        true
    } catch (e: HttpException) {
        e.code == 400 || e.code == 404
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /**
     * Fills in display names and avatars of all accounts with one request and stores them, so the
     * switcher works offline.
     */
    suspend fun refreshProfiles() {
        val logins = _accounts.value.map { it.login }
        if (logins.isEmpty()) return
        val byId = runCatching { helix.users(logins) }.getOrNull()?.associateBy { it.id } ?: return
        writeLock.withLock {
            val updated = _accounts.value.map { acc ->
                byId[acc.userId]?.let { acc.copy(displayName = it.displayName, avatarUrl = it.profileImageUrl) } ?: acc
            }
            if (updated == _accounts.value) return
            _accounts.value = updated
            persist()
            publish()
        }
    }

    // ---- Token maintenance ------------------------------------------------------------------

    /**
     * A token valid for at least a few more minutes, refreshed if needed. The old token if
     * refreshing is not possible right now, e.g. offline.
     */
    suspend fun freshToken(): String? {
        val acc = account ?: return null
        if (acc.refreshToken == null || acc.expiresAt - System.currentTimeMillis() > REFRESH_MARGIN_MS) return acc.token
        refresh(acc)
        return account?.token
    }

    /**
     * Refreshes the token now. Returns false if Twitch rejected the refresh token, which logs out.
     */
    suspend fun refresh(expected: Account? = account): Boolean = refreshLock.withLock {
        val acc = account ?: return false
        // Another caller refreshed while we waited for the lock.
        if (expected != null && acc.token != expected.token) return true
        val refreshToken = acc.refreshToken ?: run { logout(expired = true); return false }
        try {
            val t = http.postForm<TokenResponse>(
                "https://id.twitch.tv/oauth2/token",
                mapOf(
                    "client_id" to BuildConfig.TWITCH_CLIENT_ID,
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                ),
            )
            saveAccount(
                acc.copy(
                    token = t.accessToken,
                    refreshToken = t.refreshToken ?: acc.refreshToken,
                    expiresAt = System.currentTimeMillis() + t.expiresIn * 1000L,
                ),
                // The renewed account may not be the active one any more.
                makeActive = false,
            )
            true
        } catch (e: HttpException) {
            if (e.code == 400 || e.code == 401) {
                logout(expired = true)
                false
            } else true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            true // offline, try again later
        }
    }

    /**
     * [revokeReplaced] for logging into an account that is already here: its old tokens are
     * revoked. A refresh leaves them alone, Twitch retires them itself.
     */
    private suspend fun saveAccount(account: Account, makeActive: Boolean = true, revokeReplaced: Boolean = false) {
        val replaced = writeLock.withLock {
            // Replaced in place: the list order is the switcher's order.
            val at = _accounts.value.indexOfFirst { it.userId == account.userId }
            val before = _accounts.value.getOrNull(at)
            _accounts.value =
                if (at < 0) _accounts.value + account
                else _accounts.value.toMutableList().also { it[at] = account }
            if (makeActive) activeId = account.userId
            guest = false
            _sessionExpired.value = false
            persist()
            publish()
            before
        }
        if (revokeReplaced && replaced != null && replaced.token != account.token) {
            queueRevoke(listOfNotNull(replaced.token, replaced.refreshToken))
            revokePending()
        }
    }

    /** Writes the account list and the guest flag; call while holding [writeLock]. */
    private suspend fun persist() {
        // An account logged in again replaces its unreadable entry.
        val list = _accounts.value.map { it.stored() } +
            unreadable.filter { u -> _accounts.value.none { it.userId == u.userId } }
        val active = activeId
        store.edit { prefs ->
            // Not prefs.clear(): the revoke queue lives in the same store.
            if (list.isEmpty()) prefs.remove(ACCOUNTS_KEY) else prefs[ACCOUNTS_KEY] = AccountStore.encode(list)
            if (active != null) prefs[ACTIVE_KEY] = active else prefs.remove(ACTIVE_KEY)
            if (guest) prefs[GUEST_KEY] = true else prefs.remove(GUEST_KEY)
            // Keys of the old single-account format.
            LEGACY_KEYS.forEach { prefs.remove(it) }
        }
    }

    /** Publishes the active account as [state]; call while holding [writeLock]. */
    private fun publish() {
        _knownUserIds.value = (_accounts.value.map { it.userId } + unreadable.map { it.userId }).distinct()
        val active = _accounts.value.firstOrNull { it.userId == activeId }
        _state.value = when {
            active != null -> AuthState.LoggedIn(active)
            guest -> AuthState.Guest
            else -> AuthState.LoggedOut
        }
    }

    private fun Account.stored() = StoredAccount(
        login = login,
        userId = userId,
        token = TokenCipher.encrypt(token),
        refreshToken = refreshToken?.let { TokenCipher.encrypt(it) },
        expiresAt = expiresAt,
        displayName = displayName,
        avatarUrl = avatarUrl,
        scopes = scopes?.sorted(),
    )

    /** Decrypts the tokens. A lost refresh token leaves an account without one. */
    private fun StoredAccount.decrypted(): Opened<Account> {
        val plain = when (val t = TokenCipher.open(token)) {
            is Decrypted.Plain -> t.text
            Decrypted.Lost -> return Opened.Lost
            Decrypted.Unavailable -> return Opened.Unavailable
        }
        val refresh = refreshToken?.let {
            when (val r = TokenCipher.open(it)) {
                is Decrypted.Plain -> r.text
                Decrypted.Lost -> null
                Decrypted.Unavailable -> return Opened.Unavailable
            }
        }
        return Opened.Readable(
            Account(
                login = login,
                userId = userId,
                token = plain,
                refreshToken = refresh,
                expiresAt = expiresAt,
                displayName = displayName,
                avatarUrl = avatarUrl,
                scopes = scopes?.toSet(),
            ),
        )
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long = 3600,
    ) {
        /** Leaves out the tokens; see [Account.toString]. */
        override fun toString(): String = "TokenResponse(expiresIn=$expiresIn)"
    }

    companion object {
        private const val TAG = "AuthRepository"
        const val REDIRECT_URI = "http://localhost"

        /** Retry delays: one second, then two, then three. */
        private const val DECRYPT_RETRY_MS = 1_000L
        private const val REFRESH_MARGIN_MS = 10 * 60_000L

        private val ACCOUNTS_KEY = stringPreferencesKey("accounts")
        private val ACTIVE_KEY = stringPreferencesKey("active_account")
        private val GUEST_KEY = booleanPreferencesKey("guest")
        /** Encrypted tokens of logged-out accounts not yet revoked at Twitch. */
        private val REVOKE_KEY = stringPreferencesKey("revoke")

        private val TOKEN_KEY = stringPreferencesKey("token")
        private val REFRESH_KEY = stringPreferencesKey("refresh_token")
        private val EXPIRES_KEY = longPreferencesKey("expires_at")
        private val LOGIN_KEY = stringPreferencesKey("login")
        private val USER_ID_KEY = stringPreferencesKey("user_id")
        private val LEGACY_KEYS = listOf(TOKEN_KEY, REFRESH_KEY, EXPIRES_KEY, LOGIN_KEY, USER_ID_KEY)
    }
}
