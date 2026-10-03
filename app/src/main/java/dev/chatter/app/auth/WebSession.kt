package dev.chatter.app.auth

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cookies and storage Twitch's login page leaves in the WebView. They are a Twitch session of their
 * own and let anyone open the login page straight into the account.
 */
object WebSession {
    /** Clears the session. The WebView requires the main thread. */
    suspend fun clear() = withContext(Dispatchers.Main) { clearNow() }

    /** [clear] for callers already on the main thread. */
    fun clearNow() {
        // Throws while the WebView is being updated by the Play Store.
        runCatching {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
            WebStorage.getInstance().deleteAllData()
        }.onFailure { Log.w("WebSession", "Could not clear the login session: ${it.message}") }
    }
}
