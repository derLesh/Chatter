package dev.chatter.app.auth

import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chatter.app.BuildConfig
import dev.chatter.app.net.HelixApi
import dev.chatter.app.net.HttpException
import dev.chatter.app.net.postForm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
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
) {
    /** The name to put in front of a person: theirs where Twitch knows one, the login otherwise. */
    val name: String get() = displayName.ifEmpty { login }
}

sealed interface AuthState {
    data object Loading : AuthState
    data object LoggedOut : AuthState
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

    /** Loads the stored tokens (refreshing them if needed). Call once at app start. */
    suspend fun restore() {
        val p = store.data.first()
        val stored = AccountStore.decode(p[ACCOUNTS_KEY])
        // An install from before several accounts were a thing kept one account in keys of its
        // own. It becomes the first entry of the list, and the old keys go on the next write.
        val migrated = stored.isEmpty()
        val accounts = (if (migrated) listOfNotNull(legacyAccount(p)) else stored).mapNotNull { it.decrypted() }
        writeLock.withLock {
            _accounts.value = accounts
            activeId = AccountStore.activeIn(accounts.map { it.userId }, p[ACTIVE_KEY])
            // Log in optimistically so the chat can connect right away (also offline).
            publish()
            if (migrated && accounts.isNotEmpty()) persist()
        }
        if (activeId == null) return
        freshToken()
        refreshProfiles()
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
        return Uri.parse("https://id.twitch.tv/oauth2/authorize").buildUpon()
            .appendQueryParameter("response_type", "token")
            .appendQueryParameter("client_id", BuildConfig.TWITCH_CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES.joinToString(" "))
            .appendQueryParameter("state", state)
            .apply { if (forceVerify) appendQueryParameter("force_verify", "true") }
            .build().toString()
    }

    /**
     * Handles the redirect `http://localhost#access_token=...&state=...`.
     * Returns null if [url] is not our redirect, otherwise whether the login succeeded.
     */
    suspend fun handleRedirect(url: String): Result<Unit>? {
        if (!url.startsWith(REDIRECT_URI)) return null
        val uri = Uri.parse(url)
        val params = (uri.fragment ?: uri.query ?: "").split('&')
            .associate { it.substringBefore('=') to Uri.decode(it.substringAfter('=', "")) }
        params["error"]?.let { return Result.failure(IllegalStateException(params["error_description"] ?: it)) }
        if (params["state"] != pendingState) return Result.failure(IllegalStateException("State mismatch"))
        val token = params["access_token"] ?: return Result.failure(IllegalStateException("No token"))
        val result = runCatching {
            val v = helix.validate(token)
            // expires_in == 0 means the token does not expire on its own.
            val expiresAt = if (v.expiresIn > 0) System.currentTimeMillis() + v.expiresIn * 1000L else Long.MAX_VALUE
            saveAccount(Account(v.login, v.userId, token, refreshToken = null, expiresAt = expiresAt))
        }
        // After the login is in, so a picture that could not be fetched never fails one.
        if (result.isSuccess) refreshProfiles()
        return result
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

    private suspend fun forget(userId: String, expired: Boolean) {
        writeLock.withLock {
            val rest = _accounts.value.filterNot { it.userId == userId }
            if (rest.size == _accounts.value.size) return
            _accounts.value = rest
            if (activeId == userId) activeId = rest.firstOrNull()?.userId
            // Only worth explaining when nothing is left: with another account to fall back on,
            // the app never reaches the login screen that would say it.
            _sessionExpired.value = expired && rest.isEmpty()
            persist()
            publish()
        }
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

    private suspend fun saveAccount(account: Account, makeActive: Boolean = true) {
        writeLock.withLock {
            // Where the account is already in the list it is replaced where it stands: logging
            // into it again must neither list it twice nor move it, because the order of the
            // list is the order the switcher shows.
            val at = _accounts.value.indexOfFirst { it.userId == account.userId }
            _accounts.value =
                if (at < 0) _accounts.value + account
                else _accounts.value.toMutableList().also { it[at] = account }
            if (makeActive) activeId = account.userId
            _sessionExpired.value = false
            persist()
            publish()
        }
    }

    /** Writes the account list; call while holding [writeLock]. */
    private suspend fun persist() {
        val list = _accounts.value.map { it.stored() }
        val active = activeId
        store.edit { prefs ->
            if (list.isEmpty()) prefs.clear() else prefs[ACCOUNTS_KEY] = AccountStore.encode(list)
            if (active != null) prefs[ACTIVE_KEY] = active else prefs.remove(ACTIVE_KEY)
            // The single-account keys of older versions; their account is in the list now.
            LEGACY_KEYS.forEach { prefs.remove(it) }
        }
    }

    /** Publishes the active account as the state; call while holding [writeLock]. */
    private fun publish() {
        val active = _accounts.value.firstOrNull { it.userId == activeId }
        _state.value = if (active == null) AuthState.LoggedOut else AuthState.LoggedIn(active)
    }

    private fun Account.stored() = StoredAccount(
        login = login,
        userId = userId,
        token = TokenCipher.encrypt(token),
        refreshToken = refreshToken?.let { TokenCipher.encrypt(it) },
        expiresAt = expiresAt,
        displayName = displayName,
        avatarUrl = avatarUrl,
    )

    /** Null for an entry whose token cannot be read any more — a keystore key that was replaced. */
    private fun StoredAccount.decrypted(): Account? {
        val plain = TokenCipher.decrypt(token) ?: return null
        return Account(
            login = login,
            userId = userId,
            token = plain,
            refreshToken = refreshToken?.let { TokenCipher.decrypt(it) },
            expiresAt = expiresAt,
            displayName = displayName,
            avatarUrl = avatarUrl,
        )
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long = 3600,
    )

    companion object {
        const val REDIRECT_URI = "http://localhost"
        val SCOPES = listOf(
            "chat:read", "chat:edit", "user:read:emotes", "user:read:follows", "user:manage:chat_color",
            // Whispers arrive over the chat connection, but only for a token that asked for them.
            // Sending them goes through Helix, which wants the newer scope of the two.
            "whispers:read", "user:manage:whispers",
            "user:read:blocked_users", "user:manage:blocked_users",
            // The chatter list; Twitch only answers for channels the user moderates.
            "moderator:read:chatters",
            // Moderation commands (only work where the user is moderator/broadcaster).
            "moderator:manage:banned_users", "moderator:manage:chat_messages", "moderator:manage:chat_settings",
            "moderator:manage:announcements", "moderator:manage:shoutouts",
            "channel:manage:moderators", "channel:manage:vips", "channel:manage:raids",
        )
        private const val REFRESH_MARGIN_MS = 10 * 60_000L

        private val ACCOUNTS_KEY = stringPreferencesKey("accounts")
        private val ACTIVE_KEY = stringPreferencesKey("active_account")

        private val TOKEN_KEY = stringPreferencesKey("token")
        private val REFRESH_KEY = stringPreferencesKey("refresh_token")
        private val EXPIRES_KEY = longPreferencesKey("expires_at")
        private val LOGIN_KEY = stringPreferencesKey("login")
        private val USER_ID_KEY = stringPreferencesKey("user_id")
        private val LEGACY_KEYS = listOf(TOKEN_KEY, REFRESH_KEY, EXPIRES_KEY, LOGIN_KEY, USER_ID_KEY)
    }
}
