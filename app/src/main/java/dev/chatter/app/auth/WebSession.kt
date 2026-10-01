package dev.chatter.app.auth

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What Twitch's login page leaves in the app's WebView: its cookies and its storage, which is a
 * Twitch session of its own, apart from any token Chatter holds. While it is there, the login
 * page lets whoever opens it straight back in, without a password or a second factor.
 */
object WebSession {
    /** Forgets that session. The WebView wants this on the main thread. */
    suspend fun clear() = withContext(Dispatchers.Main) { clearNow() }

    /** [clear] for a caller already on the main thread, such as the WebView's own factory. */
    fun clearNow() {
        // A WebView that is being updated by the Play Store throws here; the session it would
        // have cleared cannot be read either until it is back.
        runCatching {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
            WebStorage.getInstance().deleteAllData()
        }.onFailure { Log.w("WebSession", "Could not clear the login session: ${it.message}") }
    }
}
