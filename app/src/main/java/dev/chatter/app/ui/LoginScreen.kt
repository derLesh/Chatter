package dev.chatter.app.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.chatter.app.BuildConfig
import dev.chatter.app.R
import dev.chatter.app.auth.AuthRepository
import kotlinx.coroutines.launch

/**
 * Login like DankChat: Twitch's login page in a WebView, the token is taken from the redirect.
 * Fallback: device code login (confirm a code on twitch.tv/activate in the browser).
 */
@Composable
fun LoginScreen(vm: MainViewModel) {
    var webLoginUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var webError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val url = webLoginUrl
    if (url != null) {
        BackHandler { webLoginUrl = null }
        LoginWebView(url, Modifier.fillMaxSize().safeDrawingPadding()) { redirect ->
            scope.launch {
                val result = vm.handleRedirect(redirect) ?: return@launch
                webLoginUrl = null
                webError = result.exceptionOrNull()?.let { it.message ?: it.toString() }
            }
        }
        return
    }

    val context = LocalContext.current
    fun openInBrowser(link: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (e: ActivityNotFoundException) {
            // No browser installed: the code can still be entered on another device.
        }
    }

    val state = vm.login
    if (state is LoginUi.Waiting || state is LoginUi.Starting) BackHandler { vm.cancelLogin() }
    if (state is LoginUi.Waiting) {
        LaunchedEffect(state.device.userCode) { openInBrowser(state.device.verificationUri) }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.login_subtitle), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))

            when {
                BuildConfig.TWITCH_CLIENT_ID.isEmpty() ->
                    Text(stringResource(R.string.login_missing_client_id), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)

                state is LoginUi.Starting -> CircularProgressIndicator()

                state is LoginUi.Waiting -> WaitingForConfirmation(
                    userCode = state.device.userCode,
                    onOpen = { openInBrowser(state.device.verificationUri) },
                    onCancel = vm::cancelLogin,
                )

                else -> {
                    Button(onClick = { webError = null; webLoginUrl = vm.loginUrl() }) {
                        Text(stringResource(R.string.login_button))
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { webError = null; vm.startLogin() }) {
                        Text(stringResource(R.string.login_with_code))
                    }
                    val error = webError ?: (state as? LoginUi.Failed)?.message
                    if (error != null) {
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.login_failed, error), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

/** Twitch's login page. Hands the redirect to `http://localhost#access_token=...` to [onRedirect]. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginWebView(url: String, modifier: Modifier, onRedirect: (String) -> Unit) {
    var loading by remember { mutableStateOf(true) }
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.setSupportZoom(true)
                    clearCache(true)
                    clearFormData()
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val target = request.url.toString()
                            if (!target.startsWith(AuthRepository.REDIRECT_URI)) return false
                            onRedirect(target)
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

@Composable
private fun WaitingForConfirmation(userCode: String, onOpen: () -> Unit, onCancel: () -> Unit) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    Text(stringResource(R.string.login_code_hint), textAlign = TextAlign.Center)
    Spacer(Modifier.height(16.dp))
    Text(
        text = userCode,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        letterSpacing = 4.sp,
    )
    Spacer(Modifier.height(20.dp))
    Button(onClick = onOpen) { Text(stringResource(R.string.login_open_twitch)) }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(userCode)) }) { Text(stringResource(R.string.login_copy_code)) }
    Spacer(Modifier.height(24.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.login_waiting), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
}
