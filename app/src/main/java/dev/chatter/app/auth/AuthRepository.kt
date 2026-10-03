package dev.chatter.app.auth

import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import android.util.Log
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
    /**
     * What Twitch calls the account and the picture it wears. The login alone would do to chat
     * with, but a switcher that shows neither is a list of lowercase words.
     */
    val displayName: String = "",
    val avatarUrl: String = "",
    /** What Twitch lets [token] do; null for an account stored before the app kept it. */
    val scopes: Set<String>? = null,
) {
    /** The name to put in front of a person: theirs where Twitch knows one, the login otherwise. */
    val name: String get() = displayName.ifEmpty { login }

    /** What Chatter uses that this login was not given; see [TwitchScopes.missing]. */
    val missingScopes: Set<String> get() = TwitchScopes.missing(scopes)

    /**
     * Everything but the tokens. A data class would print them, and an account ends up in a
     * string more easily than anybody means it to — a log line, an assertion message, a crash
     * report that prints the state it crashed in — and from there in a bug report on GitHub.
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
     * Reading without an account. Twitch lets anybody read a chat, so channels are added and
     * followed as usual; writing, whispers, Twitch's own badges and everything else its API
     * answers need a login.
     */
    data object Guest : AuthState
    data class LoggedIn(val account: Account) : AuthState
}

/**
 * Twitch login (like DankChat): the implicit OAuth flow in a WebView, where Twitch redirects to
 * `http://localhost#access_token=...` and the WebView intercepts it. Those tokens are long-lived and
 * cannot be refreshed; once Twitch rejects one, the user logs in again. Accounts stored by the older
 * device code login still carry a refresh token, which [refresh] keeps renewing.
 *
 * Several accounts can be logged in at once. Only one of them is the account the app acts as —
 * [state] is that one — and the rest wait in [accounts] until [switchTo] picks one of them.
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
     * Accounts whose tokens the keystore would not decrypt at start, kept exactly as they were
     * stored. They are not logged in — there is no token to log in with — but they are not
     * logged out either: [persist] writes them back untouched, and the next start tries again.
     */
    private var unreadable: List<StoredAccount> = emptyList()

    private val _knownUserIds = MutableStateFlow<List<String>>(emptyList())
    /**
     * The user id of every account on the phone, the [unreadable] ones included. What belongs to
     * an account — its inbox, its whispers — is kept for as long as it is in here.
     */
    val knownUserIds: StateFlow<List<String>> = _knownUserIds

    private val _sessionExpired = MutableStateFlow(false)
    /**
     * True when the login ended on its own — the token ran out or was revoked — as opposed to the
     * user asking to be logged out. The difference is invisible from the login screen otherwise,
     * and being asked to log in again out of nowhere is worth an explanation.
     */
    val sessionExpired: StateFlow<Boolean> = _sessionExpired

    /** Set by the AppContainer to break the Helix <-> Auth construction cycle. */
    lateinit var helix: HelixApi

    private val refreshLock = Mutex()

    /** Held for every change to the account list, so two of them cannot write over each other. */
    private val writeLock = Mutex()

    private var activeId: String? = null

    val account: Account? get() = (state.value as? AuthState.LoggedIn)?.account
    val token: String? get() = account?.token

    /** Whether there is a chat to read: an account is logged in, or a guest is reading. */
    val canRead: Boolean get() = state.value.let { it is AuthState.LoggedIn || it is AuthState.Guest }

    /**
     * Whether the app reads as a guest while no account is logged in. Logging in ends it, so that
     * logging that account out again leads to the login screen and not back into the chat.
     */
    private var guest = false

    /** Loads the stored tokens (refreshing them if needed). Call once at app start. */
    suspend fun restore() {
        val p = store.data.first()
        val stored = AccountStore.decode(p[ACCOUNTS_KEY])
        // An install from before several accounts were a thing kept one account in keys of its
        // own. It becomes the first entry of the list, and the old keys go on the next write.
        val migrated = stored.isEmpty()
        val (accounts, unread) = decryptAll(if (migrated) listOfNotNull(legacyAccount(p)) else stored)
        writeLock.withLock {
            _accounts.value = accounts
            unreadable = unread
            activeId = AccountStore.activeIn(accounts.map { it.userId }, p[ACTIVE_KEY])
            guest = p[GUEST_KEY] == true
            // Log in optimistically so the chat can connect right away (also offline).
            publish()
            if (migrated && accounts.isNotEmpty()) persist()
        }
        // Tokens of a logout that happened offline; Twitch is told about them now.
        revokePending()
        if (activeId == null) return
        freshToken()
        refreshProfiles()
        learnScopes()
    }

    /**
     * Asks Twitch what the tokens of accounts stored before the app kept their scopes may do,
     * so the account page can say when a login lacks something. Offline it waits for the next start;
     * until then such an account is let try every command, as it always was.
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

    /** The stored accounts with their tokens decrypted, and the ones set aside; see [AccountStore.readAll]. */
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
     * [forceVerify] makes Twitch ask again who is logging in even where it could answer from a
     * session it still has. Adding a second account needs that: without it Twitch hands a token
     * for whoever was there before straight back, which is the account the user already has.
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
     * Handles the redirect `http://localhost#access_token=...&state=...`.
     * Returns null if [url] is not our redirect, otherwise whether the login succeeded.
     */
    suspend fun handleRedirect(url: String): Result<Unit>? {
        if (!LoginUrls.isRedirect(url)) return null
        // A state is good for one answer. Whatever this one turns out to be, the next redirect
        // cannot reuse it — and with no login running, none is expected at all.
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
        // After the login is in, so a picture that could not be fetched never fails one.
        if (result.isSuccess) refreshProfiles()
        return result
    }

    // ---- Reading as a guest -------------------------------------------------------------------

    /** Reads chats without logging in, until an account is added; see [AuthState.Guest]. */
    suspend fun continueAsGuest() = setGuest(true)

    /** Back to the login screen. The channels stay, for whoever logs in. */
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

    /** Makes [userId] the account the app reads and writes as. Does nothing for an unknown one. */
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

    /**
     * Logs one account out. The others stay, and if it was the active one the app carries on as
     * the next of them rather than showing the login screen.
     */
    suspend fun remove(userId: String) = forget(userId, expired = false)

    /** [expired] when Twitch ended the session, not the user; see [sessionExpired]. */
    suspend fun logout(expired: Boolean = false) {
        forget(activeId ?: return, expired)
    }

    /**
     * Takes the account off the phone, and — unless Twitch ended the session itself — off Twitch
     * as well. Dropping the token here alone would leave it valid for as long as Twitch lets a
     * token live, which for the WebView login is months, wherever a copy of it went.
     */
    private suspend fun forget(userId: String, expired: Boolean) {
        val gone = writeLock.withLock {
            val gone = _accounts.value.firstOrNull { it.userId == userId } ?: return
            val rest = _accounts.value - gone
            _accounts.value = rest
            if (activeId == userId) activeId = rest.firstOrNull()?.userId
            // Only worth explaining when nothing is left: with another account to fall back on,
            // the app never reaches the login screen that would say it.
            _sessionExpired.value = expired && rest.isEmpty()
            persist()
            publish()
            gone
        }
        // Twitch's own session in the WebView would let the next person straight back in. Which
        // account it belongs to cannot be told, so it goes whichever one was logged out; adding
        // an account clears it anyway.
        WebSession.clear()
        if (expired) return
        queueRevoke(listOfNotNull(gone.token, gone.refreshToken))
        revokePending()
    }

    // ---- Revoking tokens --------------------------------------------------------------------

    private val revokeLock = Mutex()

    /**
     * Remembers [tokens] to be revoked at Twitch, encrypted like the accounts are. Written before
     * the first try, so a logout while offline, or a process killed in the middle of one, still
     * gets to Twitch the next time the app starts.
     */
    private suspend fun queueRevoke(tokens: List<String>) {
        if (tokens.isEmpty()) return
        store.edit { p ->
            p[REVOKE_KEY] = AccountStore.encodeTokens(AccountStore.decodeTokens(p[REVOKE_KEY]) + tokens.map(TokenCipher::encrypt))
        }
    }

    /** Revokes every queued token Twitch can be reached for, and keeps the rest for later. */
    suspend fun revokePending() = revokeLock.withLock {
        val queued = AccountStore.decodeTokens(store.data.first()[REVOKE_KEY])
        if (queued.isEmpty()) return@withLock
        // A token that cannot be decrypted any more cannot be revoked either, so it is done with.
        // One the keystore did not answer for stays queued: it is still valid at Twitch.
        val left = queued.filter { encrypted ->
            when (val t = TokenCipher.open(encrypted)) {
                is Decrypted.Plain -> !revoke(t.text)
                Decrypted.Lost -> false
                Decrypted.Unavailable -> true
            }
        }
        store.edit { p ->
            // Whatever was queued while this ran stays queued.
            val now = AccountStore.decodeTokens(p[REVOKE_KEY]) - queued.toSet() + left
            if (now.isEmpty()) p.remove(REVOKE_KEY) else p[REVOKE_KEY] = AccountStore.encodeTokens(now)
        }
    }

    /**
     * Asks Twitch to revoke [token]. True when it is done with — revoked now, or already
     * invalid, which Twitch answers with a 400 — and false when it should be tried again.
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
     * Fills in each account's display name and picture. Logging in only ever says the login, so
     * without this an account switcher would be a list of lowercase words; one request covers
     * every account at once, and what it brings back is stored so the list needs no network.
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
     * Returns a token that is valid for at least a few more minutes, refreshing it if necessary.
     * Returns the old token if refreshing is not possible right now (e.g. offline).
     */
    suspend fun freshToken(): String? {
        val acc = account ?: return null
        if (acc.refreshToken == null || acc.expiresAt - System.currentTimeMillis() > REFRESH_MARGIN_MS) return acc.token
        refresh(acc)
        return account?.token
    }

    /** Refreshes the token now. Returns false if Twitch rejected the refresh token (logged out). */
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
                // A refresh says nothing about which account the user is on: the one being
                // renewed may be an old active account the user has already switched away from.
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
            true // offline: try again later
        }
    }

    /**
     * [revokeReplaced] for a new login into an account that is already here: the token it had
     * before is not needed any more, and a token nobody needs should not stay valid either. A
     * refresh leaves it alone; Twitch retires the old token on its own then.
     */
    private suspend fun saveAccount(account: Account, makeActive: Boolean = true, revokeReplaced: Boolean = false) {
        val replaced = writeLock.withLock {
            // Where the account is already in the list it is replaced where it stands: logging
            // into it again must neither list it twice nor move it, because the order of the
            // list is the order the switcher shows.
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

    /** Writes the account list and whether a guest is reading; call while holding [writeLock]. */
    private suspend fun persist() {
        // An account logged in again since is the new one; its unreadable entry goes.
        val list = _accounts.value.map { it.stored() } +
            unreadable.filter { u -> _accounts.value.none { it.userId == u.userId } }
        val active = activeId
        store.edit { prefs ->
            // Not prefs.clear(): the tokens still waiting to be revoked live in the same store.
            if (list.isEmpty()) prefs.remove(ACCOUNTS_KEY) else prefs[ACCOUNTS_KEY] = AccountStore.encode(list)
            if (active != null) prefs[ACTIVE_KEY] = active else prefs.remove(ACTIVE_KEY)
            if (guest) prefs[GUEST_KEY] = true else prefs.remove(GUEST_KEY)
            // The single-account keys of older versions; their account is in the list now.
            LEGACY_KEYS.forEach { prefs.remove(it) }
        }
    }

    /** Publishes the active account as the state; call while holding [writeLock]. */
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

    /**
     * The account with its tokens decrypted. [Opened.Lost] for an entry whose token cannot be
     * read any more — a keystore key that was replaced — and [Opened.Unavailable] while the
     * keystore does not answer. A refresh token that is lost leaves the account as one without.
     */
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
        /** Without the tokens, for the same reason as [Account.toString]. */
        override fun toString(): String = "TokenResponse(expiresIn=$expiresIn)"
    }

    companion object {
        private const val TAG = "AuthRepository"
        const val REDIRECT_URI = "http://localhost"

        /** The keystore is asked again after one second, then two, then three. */
        private const val DECRYPT_RETRY_MS = 1_000L
        private const val REFRESH_MARGIN_MS = 10 * 60_000L

        private val ACCOUNTS_KEY = stringPreferencesKey("accounts")
        private val ACTIVE_KEY = stringPreferencesKey("active_account")
        private val GUEST_KEY = booleanPreferencesKey("guest")
        /** Encrypted tokens of logged-out accounts that Twitch has not confirmed revoking yet. */
        private val REVOKE_KEY = stringPreferencesKey("revoke")

        private val TOKEN_KEY = stringPreferencesKey("token")
        private val REFRESH_KEY = stringPreferencesKey("refresh_token")
        private val EXPIRES_KEY = longPreferencesKey("expires_at")
        private val LOGIN_KEY = stringPreferencesKey("login")
        private val USER_ID_KEY = stringPreferencesKey("user_id")
        private val LEGACY_KEYS = listOf(TOKEN_KEY, REFRESH_KEY, EXPIRES_KEY, LOGIN_KEY, USER_ID_KEY)
    }
}
