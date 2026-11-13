package me.lesh.chatter.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import me.lesh.chatter.auth.LoginUrls
import me.lesh.chatter.auth.WebSession

/**
 * Twitch's login page. Passes the `http://localhost#access_token=...` redirect to [onRedirect] and
 * lets the page navigate only within Twitch (see [LoginUrls]).
 *
 * [signedOut] clears Twitch's WebView session first, for adding another account; otherwise Twitch
 * offers the last logged-in account again.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginWebView(url: String, modifier: Modifier, signedOut: Boolean = false, onRedirect: (String) -> Unit) {
    var loading by remember { mutableStateOf(true) }
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                if (signedOut) WebSession.clearNow()
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.setSupportZoom(true)
                    // Set explicitly instead of relying on platform defaults.
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    clearCache(true)
                    clearFormData()
                    webViewClient = object : WebViewClient() {
                        // Twitch's page can navigate to the redirect twice; the second would fail
                        // the login the first just finished.
                        private var answered = false

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val target = request.url.toString()
                            if (LoginUrls.isRedirect(target)) {
                                if (!answered) onRedirect(target)
                                answered = true
                                return true
                            }
                            // Frames inside the page (e.g. the captcha) belong to it.
                            if (!request.isForMainFrame || LoginUrls.staysInLogin(target)) return false
                            // Anything else opens in the browser: the WebView has no address bar
                            // showing which site the user is typing into.
                            val scheme = request.url.scheme?.lowercase()
                            if (scheme == "https" || scheme == "http") {
                                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                            }
                            return true
                        }

                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            loading = true
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            loading = false
                        }
                    }
                    loadUrl(url)
                }
            },
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
