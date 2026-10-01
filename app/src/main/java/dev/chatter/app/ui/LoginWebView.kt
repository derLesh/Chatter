package dev.chatter.app.ui

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
import dev.chatter.app.auth.LoginUrls
import dev.chatter.app.auth.WebSession

/**
 * Twitch's login page. Hands the redirect to `http://localhost#access_token=...` to [onRedirect],
 * and lets the page go nowhere but Twitch (see [LoginUrls]).
 *
 * [signedOut] throws away whatever Twitch left in the WebView first. Adding a second account is
 * the reason: Twitch remembers who was last logged in, and the page would offer that account
 * again rather than ask which one this is meant to be.
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
                    // Twitch's page has no business with the phone's files; said here rather
                    // than left to whatever the platform's default is.
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    clearCache(true)
                    clearFormData()
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val target = request.url.toString()
                            if (LoginUrls.isRedirect(target)) {
                                onRedirect(target)
                                return true
                            }
                            // Frames inside the page — the captcha is one — are part of it.
                            if (!request.isForMainFrame || LoginUrls.staysInLogin(target)) return false
                            // Anything else leaves the login: in here it would be a browser with no
                            // address bar, where nobody can see which site they are typing into.
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
