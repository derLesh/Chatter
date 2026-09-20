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
import kotlinx.coroutines.delay
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
)

sealed interface AuthState {
    data object Loading : AuthState
    data object LoggedOut : AuthState
    data class LoggedIn(val account: Account) : AuthState
}

/** What the user has to confirm on twitch.tv/activate. */
data class DeviceLogin(
    val userCode: String,
    val verificationUri: String,
    internal val deviceCode: String,
    internal val intervalSeconds: Int,
    internal val expiresAt: Long,
)

/**
 * Twitch login. The primary way (like DankChat) is the implicit OAuth flow in a WebView:
 * Twitch redirects to `http://localhost#access_token=...`, which the WebView intercepts.
 * Those tokens are long-lived and cannot be refreshed; once Twitch rejects one, the user logs in again.
 *
 * As a fallback there is the Device Code Flow (confirm a code on twitch.tv/activate in the browser).
 * Its tokens expire after a few hours and are renewed with the refresh token.
 */
class AuthRepository(
    private val store: DataStore<Preferences>,
    private val http: OkHttpClient,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state

    /** Set by the AppContainer to break the Helix <-> Auth construction cycle. */
    lateinit var helix: HelixApi

    private val refreshLock = Mutex()

    val account: Account? get() = (state.value as? AuthState.LoggedIn)?.account
    val token: String? get() = account?.token

    /** Loads the stored tokens (refreshing them if needed). Call once at app start. */
    suspend fun restore() {
        val p = store.data.first()
        val token = p[TOKEN_KEY]?.let { TokenCipher.decrypt(it) }
        val login = p[LOGIN_KEY]
        val userId = p[USER_ID_KEY]
        if (token == null || login == null || userId == null) {
            _state.value = AuthState.LoggedOut
            return
        }
        val refresh = p[REFRESH_KEY]?.let { TokenCipher.decrypt(it) }
        // Log in optimistically so the chat can connect right away (also offline).
        _state.value = AuthState.LoggedIn(Account(login, userId, token, refresh, p[EXPIRES_KEY] ?: 0L))
        freshToken()
    }

    // ---- WebView login (implicit flow) -----------------------------------------------------

    /** Expected `state` parameter of the running login, to reject forged redirects. */
    private var pendingState: String? = null

    fun authorizeUrl(): String {
        val state = UUID.randomUUID().toString()
        pendingState = state
        return Uri.parse("https://id.twitch.tv/oauth2/authorize").buildUpon()
            .appendQueryParameter("response_type", "token")
            .appendQueryParameter("client_id", BuildConfig.TWITCH_CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES.joinToString(" "))
            .appendQueryParameter("state", state)
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
        return runCatching {
            val v = helix.validate(token)
            // expires_in == 0 means the token does not expire on its own.
            val expiresAt = if (v.expiresIn > 0) System.currentTimeMillis() + v.expiresIn * 1000L else Long.MAX_VALUE
            saveAccount(Account(v.login, v.userId, token, refreshToken = null, expiresAt = expiresAt))
        }
    }

    // ---- Device code login (fallback) -------------------------------------------------------

    suspend fun startDeviceLogin(): DeviceLogin {
        val r = http.postForm<DeviceCodeResponse>(
            "https://id.twitch.tv/oauth2/device",
            mapOf("client_id" to BuildConfig.TWITCH_CLIENT_ID, "scopes" to SCOPES.joinToString(" ")),
        )
        return DeviceLogin(
            userCode = r.userCode,
            verificationUri = r.verificationUri,
            deviceCode = r.deviceCode,
            intervalSeconds = r.interval.coerceAtLeast(1),
            expiresAt = System.currentTimeMillis() + r.expiresIn * 1000L,
        )
    }

    /** Polls until the user confirmed the code (success) or it expired / was denied (failure). */
    suspend fun awaitDeviceLogin(login: DeviceLogin): Result<Unit> {
        var interval = login.intervalSeconds
        while (System.currentTimeMillis() < login.expiresAt) {
            delay(interval * 1000L)
            try {
                val t = http.postForm<TokenResponse>(
                    "https://id.twitch.tv/oauth2/token",
                    mapOf(
                        "client_id" to BuildConfig.TWITCH_CLIENT_ID,
                        "scopes" to SCOPES.joinToString(" "),
                        "device_code" to login.deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    ),
                )
                return runCatching { saveTokens(t) }
            } catch (e: HttpException) {
                when {
                    "authorization_pending" in e.body -> Unit
                    "slow_down" in e.body -> interval += 5
                    else -> return Result.failure(e)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Network hiccup while polling: keep trying until the code expires.
            }
        }
        return Result.failure(IllegalStateException("expired"))
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
        val refreshToken = acc.refreshToken ?: run { logout(); return false }
        try {
            val t = http.postForm<TokenResponse>(
                "https://id.twitch.tv/oauth2/token",
                mapOf(
                    "client_id" to BuildConfig.TWITCH_CLIENT_ID,
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                ),
            )
            saveTokens(t, known = acc)
            true
        } catch (e: HttpException) {
            if (e.code == 400 || e.code == 401) {
                logout()
                false
            } else true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            true // offline: try again later
        }
    }

    suspend fun logout() {
        store.edit { it.clear() }
        _state.value = AuthState.LoggedOut
    }

    private suspend fun saveTokens(t: TokenResponse, known: Account? = null) {
        // After a refresh the user is the same; otherwise ask Twitch who logged in.
        val (login, userId) = known?.let { it.login to it.userId }
            ?: helix.validate(t.accessToken).let { it.login to it.userId }
        saveAccount(
            Account(
                login = login,
                userId = userId,
                token = t.accessToken,
                refreshToken = t.refreshToken ?: known?.refreshToken,
                expiresAt = System.currentTimeMillis() + t.expiresIn * 1000L,
            )
        )
    }

    private suspend fun saveAccount(account: Account) {
        store.edit {
            it[TOKEN_KEY] = TokenCipher.encrypt(account.token)
            if (account.refreshToken != null) it[REFRESH_KEY] = TokenCipher.encrypt(account.refreshToken)
            else it.remove(REFRESH_KEY)
            it[EXPIRES_KEY] = account.expiresAt
            it[LOGIN_KEY] = account.login
            it[USER_ID_KEY] = account.userId
        }
        _state.value = AuthState.LoggedIn(account)
    }

    @Serializable
    private data class DeviceCodeResponse(
        @SerialName("device_code") val deviceCode: String,
        @SerialName("user_code") val userCode: String,
        @SerialName("verification_uri") val verificationUri: String,
        @SerialName("expires_in") val expiresIn: Long = 1800,
        val interval: Int = 5,
    )

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
            "user:read:blocked_users", "user:manage:blocked_users",
            // The chatter list; Twitch only answers for channels the user moderates.
            "moderator:read:chatters",
            // Moderation commands (only work where the user is moderator/broadcaster).
            "moderator:manage:banned_users", "moderator:manage:chat_messages", "moderator:manage:chat_settings",
            "moderator:manage:announcements", "moderator:manage:shoutouts",
            "channel:manage:moderators", "channel:manage:vips", "channel:manage:raids",
        )
        private const val REFRESH_MARGIN_MS = 10 * 60_000L

        private val TOKEN_KEY = stringPreferencesKey("token")
        private val REFRESH_KEY = stringPreferencesKey("refresh_token")
        private val EXPIRES_KEY = longPreferencesKey("expires_at")
        private val LOGIN_KEY = stringPreferencesKey("login")
        private val USER_ID_KEY = stringPreferencesKey("user_id")
    }
}
