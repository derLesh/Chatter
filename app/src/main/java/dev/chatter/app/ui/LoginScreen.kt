package dev.chatter.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.chatter.app.BuildConfig
import dev.chatter.app.R
import dev.chatter.app.auth.AuthRepository
import dev.chatter.app.ui.theme.isAppInDarkTheme
import dev.chatter.app.ui.theme.readableNameColor
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/** Login like DankChat: Twitch's login page in a WebView, the token is taken from the redirect. */
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

    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            OnboardingBackdrop(Modifier.fillMaxSize())
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 32.dp),
            ) {
                Spacer(Modifier.weight(1f))
                AppMark()
                Spacer(Modifier.height(28.dp))
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.login_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))

                when {
                    BuildConfig.TWITCH_CLIENT_ID.isEmpty() ->
                        Text(stringResource(R.string.login_missing_client_id), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)

                    else -> {
                        Button(
                            onClick = { webError = null; webLoginUrl = vm.loginUrl() },
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            Text(stringResource(R.string.login_button), style = MaterialTheme.typography.titleMedium)
                        }
                        webError?.let {
                            Spacer(Modifier.height(16.dp))
                            Text(stringResource(R.string.login_failed, it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

/** The app icon on a tinted squircle, the way the launcher shows it. */
@Composable
private fun AppMark() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(112.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ),
            ),
    ) {
        Icon(
            painterResource(R.drawable.ic_chatter_monochrome),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(104.dp),
        )
    }
}

/**
 * What sits behind the onboarding: colour blobs and outlined shapes in the theme's own colors, and
 * a chat scrolling past so the screen shows what the app is for. Both are slow and heavily faded.
 */
@Composable
private fun OnboardingBackdrop(modifier: Modifier) {
    val surface = MaterialTheme.colorScheme.surface
    Box(modifier) {
        ColorShapes(Modifier.fillMaxSize())
        ScrollingChat(Modifier.fillMaxSize())
        // Dims the chat towards the middle, where the logo and the buttons sit.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.0f to surface.copy(alpha = 0.55f),
                    0.28f to surface.copy(alpha = 0.92f),
                    0.80f to surface.copy(alpha = 0.92f),
                    1.0f to surface.copy(alpha = 0.72f),
                ),
            ),
        )
    }
}

/** Colour blobs and outlined shapes, drifting slowly. */
@Composable
private fun ColorShapes(modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "backdrop")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 40_000, easing = LinearEasing)),
        label = "drift",
    )

    Canvas(modifier) {
        // Blobs: centre as a fraction of the canvas, radius as a fraction of its width.
        blob(scheme.primary, 0.14f, 0.10f, 0.95f, 0.55f, drift, 0f)
        blob(scheme.tertiary, 0.92f, 0.20f, 0.75f, 0.50f, drift, 2.1f)
        blob(scheme.secondary, 0.80f, 0.78f, 0.90f, 0.45f, drift, 4.2f)
        blob(scheme.primary, 0.06f, 0.94f, 0.70f, 0.40f, drift, 5.4f)

        // Outlined shapes, kept clear of the title and the buttons at the bottom.
        val outline = scheme.outline.copy(alpha = 0.30f)
        val stroke = Stroke(width = 2.dp.toPx())
        val w = size.width
        drawCircle(outline, radius = w * 0.26f, center = Offset(w * 0.88f, size.height * 0.46f), style = stroke)
        rotate(degrees = drift * 6f, pivot = Offset(w * 0.14f, size.height * 0.40f)) {
            drawRoundRect(
                color = outline,
                topLeft = Offset(w * 0.14f - w * 0.15f, size.height * 0.40f - w * 0.15f),
                size = Size(w * 0.30f, w * 0.30f),
                cornerRadius = CornerRadius(w * 0.10f),
                style = stroke,
            )
        }
        drawRoundRect(
            color = outline,
            topLeft = Offset(w * 0.30f, size.height * 0.12f),
            size = Size(w * 0.46f, w * 0.17f),
            cornerRadius = CornerRadius(w * 0.085f),
            style = stroke,
        )
    }
}

/** One soft radial blob, nudged along a small circle by [drift] so the background never sits still. */
private fun DrawScope.blob(color: Color, x: Float, y: Float, radius: Float, alpha: Float, drift: Float, phase: Float) {
    val wander = size.width * 0.06f
    val center = Offset(
        x = size.width * x + cos(drift + phase) * wander,
        y = size.height * y + sin(drift + phase) * wander,
    )
    val r = size.width * radius
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
            center = center,
            radius = r,
        ),
        radius = r,
        center = center,
    )
}

/**
 * Decorative chat lines for the onboarding backdrop. Emote-heavy and language-neutral, so they read
 * the same in every locale; the logins are made up.
 */
private val FauxChat = listOf(
    "kappakiosk" to "LUL LUL LUL",
    "lurker_no7" to "that clutch was actually insane",
    "pogsalot" to "PogChamp",
    "bitsandbytes" to "gg wp",
    "streamsnipe_r" to "KEKW",
    "chatterfan" to "first time here, hi chat o/",
    "nightlyowl" to "Pepega Clap",
    "emoteenjoyer" to "monkaS monkaS",
    "modsarehere" to "chat calm down",
    "pixelpirate" to "5Head play",
    "quietviewer" to "W stream",
    "copiumdealer" to "COPIUM",
    "saltysponge" to "no shot that landed",
    "vibecheck99" to "widepeepoHappy",
    "aimbotandy" to "EZ Clap",
    "kekwkeeper" to "OMEGALUL",
    "sub_since_2019" to "resub gang",
    "hypetrainhal" to "HYPERS",
    "frameperfect" to "Sadge",
    "chatterlover" to "this app goes hard",
)

/** The faux chat, scrolling upwards forever. Two copies stacked make the loop seamless. */
@Composable
private fun ScrollingChat(modifier: Modifier) {
    var blockHeight by remember { mutableIntStateOf(0) }
    val transition = rememberInfiniteTransition(label = "chat")
    val scroll by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 60_000, easing = LinearEasing)),
        label = "scroll",
    )
    val dark = isAppInDarkTheme()

    Box(modifier.clipToBounds().alpha(0.7f).blur(1.dp)) {
        Column(
            Modifier.fillMaxWidth()
                // Both copies together are taller than the screen, so the column must overflow.
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .graphicsLayer { translationY = -scroll * blockHeight },
        ) {
            repeat(2) { copy ->
                Column(
                    Modifier.fillMaxWidth()
                        .then(if (copy == 0) Modifier.onSizeChanged { blockHeight = it.height } else Modifier),
                ) {
                    FauxChat.forEach { (login, message) ->
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(color = readableNameColor(null, login, dark), fontWeight = FontWeight.Bold)) {
                                    append(login)
                                }
                                append(": $message")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp),
                        )
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
