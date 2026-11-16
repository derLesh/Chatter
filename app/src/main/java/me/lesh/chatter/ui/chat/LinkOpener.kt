package me.lesh.chatter.ui.chat

import android.app.Application
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsCallback
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.UriHandler
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import me.lesh.chatter.R
import me.lesh.chatter.chat.PostLinks
import me.lesh.chatter.chat.PostSite
import me.lesh.chatter.debug.AppLog
import me.lesh.chatter.service.AddImageHostReceiver
import me.lesh.chatter.settings.Settings
import me.lesh.chatter.ui.theme.barColor

/**
 * Opens web links over the app as a Custom Tab of the user's own browser, so they stay in Chatter.
 * The browser loads the page with the user's logins and blockers, and Chatter never sees it, which
 * an own WebView could not promise. A link an installed app handles still goes to that app; for
 * the sites that get a card (X, Bluesky, YouTube, Twitch: [PostSite]) only when
 * [Settings.postsInTheirApp]. A browser without Custom Tabs gets the link the usual way.
 */
@Composable
fun rememberLinkOpener(settings: Settings, tabs: LinkTabs): UriHandler {
    val platform = LocalUriHandler.current
    val context = LocalContext.current
    // Partial Custom Tabs only open as a sheet when the browser knows the calling app, through
    // a session or a start for a result; the result also says when the tab is gone.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tabs.closed() }
    val colors = MaterialTheme.colorScheme
    val window = LocalWindowInfo.current.containerSize
    val look by rememberUpdatedState(
        TabLook(
            toolbarColor = colors.barColor.toArgb(),
            dark = colors.background.luminance() < 0.5f,
            windowWidthPx = window.width,
            windowHeightPx = window.height,
            fullScreen = settings.linksFullScreen,
            addImageHost = settings.inlineImages,
        ),
    )
    // A rotation keeps the tab and its session; only a tap waiting for it must not reach the
    // launcher of the screen that is gone.
    DisposableEffect(tabs) { onDispose { tabs.dropWaiting() } }
    val inApp = settings.linksInApp
    val postsInTheirApp = settings.postsInTheirApp
    return remember(inApp, postsInTheirApp, platform, context, launcher, tabs) {
        object : UriHandler {
            override fun openUri(uri: String) {
                // A newer tap wins, wherever its link goes.
                tabs.dropWaiting()
                val link = lowercaseScheme(uri)
                if (inApp && isWebLink(link)) {
                    val target = link.toUri()
                    val toApp = postsInTheirApp || PostLinks.find(link, PostSite.entries.toSet()) == null
                    if (toApp && openInOwnApp(context, target)) return
                    // Only the default browser: another one would surprise with other logins and
                    // another look.
                    val browser = CustomTabsClient.getPackageName(context, emptyList())
                    if (browser != null) {
                        tabs.connect(browser) { session ->
                            val intent = tabIntent(context, session, look).intent.setPackage(browser).setData(target)
                            try {
                                launcher.launch(intent)
                                tabs.opened()
                            } catch (_: ActivityNotFoundException) {
                                AppLog.w(TAG, "No Custom Tab in $browser")
                                tabs.closed()
                                openElsewhere(platform, link)
                            }
                        }
                        return
                    }
                }
                openElsewhere(platform, link)
            }
        }
    }
}

/** Throws when no app at all takes the link; a tap on it must not close Chatter. */
private fun openElsewhere(platform: UriHandler, link: String) {
    runCatching { platform.openUri(link) }.onFailure { AppLog.w(TAG, "No app opens this link", it) }
}

/** True for the links a Custom Tab can show; `mailto:`, `tel:` and app links go to their apps. */
fun isWebLink(uri: String): Boolean =
    uri.startsWith("https://", ignoreCase = true) || uri.startsWith("http://", ignoreCase = true)

/**
 * [uri] with its scheme in lower case. Chat writes `Https://` when a keyboard capitalises the
 * first word, and Android matches schemes case-sensitively, so no app or browser would take it.
 */
fun lowercaseScheme(uri: String): String {
    val colon = uri.indexOf(':')
    if (colon <= 0) return uri
    val scheme = uri.substring(0, colon)
    if (!scheme.all { it.isLetterOrDigit() || it in "+-." }) return uri
    return scheme.lowercase() + uri.substring(colon)
}

private class TabLook(
    val toolbarColor: Int,
    val dark: Boolean,
    val windowWidthPx: Int,
    val windowHeightPx: Int,
    val fullScreen: Boolean,
    val addImageHost: Boolean,
)

private fun tabIntent(context: Context, session: CustomTabsSession?, look: TabLook): CustomTabsIntent {
    val colors = CustomTabColorSchemeParams.Builder()
        .setToolbarColor(look.toolbarColor)
        .setNavigationBarColor(look.toolbarColor)
        .build()
    val builder = CustomTabsIntent.Builder()
        .setDefaultColorSchemeParams(colors)
        .setColorScheme(if (look.dark) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT)
        .setShowTitle(true)
        .setShareState(CustomTabsIntent.SHARE_STATE_ON)
        .setToolbarCornerRadiusDp(TOOLBAR_CORNER_DP)
    session?.let(builder::setSession)
    if (!look.fullScreen) {
        // A sheet over the lower part keeps the chat above it in view and usable; dragged up, it
        // takes the whole screen. On wide screens it opens as a side sheet instead.
        builder.setInitialActivityHeightPx(look.windowHeightPx * 2 / 3, CustomTabsIntent.ACTIVITY_HEIGHT_ADJUSTABLE)
        builder.setInitialActivityWidthPx(look.windowWidthPx * 45 / 100)
    }
    if (look.addImageHost) {
        // The browser fills in the page's address before it sends this, so it must stay mutable;
        // it names its receiver, so nothing else can catch it.
        val addHost = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, AddImageHostReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        builder.addMenuItem(context.getString(R.string.link_menu_add_image_host), addHost)
    }
    return builder.build()
}

/** Hands a link to an installed app that claims it, never to a browser. */
private fun openInOwnApp(context: Context, target: Uri): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, target)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER),
    )
    true
} catch (_: ActivityNotFoundException) {
    false
}

/**
 * Where the open link's sheet is, so the chat can move up out of its way. The browser reports it
 * through a session, which is bound only while a tab is open: a binding outlasting it would keep
 * the browser's process around for nothing. A view model of the activity, so the session and what
 * it said outlast a rotation.
 */
@Stable
class LinkTabs(application: Application) : AndroidViewModel(application) {
    /** What the browser last said about its sheet; null while no tab shows or it covers all. */
    var sheet by mutableStateOf<TabSheet?>(null)
        private set

    /** From the tap on a link until its tab is closed. */
    var showing by mutableStateOf(false)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var connection: CustomTabsServiceConnection? = null

    /** Counts taps and closes, so a session arriving for an older tap or a gone screen is dropped. */
    private var attempt = 0

    private val callback = object : CustomTabsCallback() {
        override fun onActivityLayout(left: Int, top: Int, right: Int, bottom: Int, state: Int, extras: Bundle) {
            main.post { if (showing) sheet = TabSheet.of(left, top, right, state) }
        }
    }

    /**
     * Binds to [browser] and hands [ready] a session, or null when the browser offers none in
     * time; the tab then opens without one and the chat stays where it is.
     */
    fun connect(browser: String, ready: (CustomTabsSession?) -> Unit) {
        closed()
        val thisAttempt = attempt
        val handOut = { session: CustomTabsSession? ->
            if (attempt == thisAttempt) {
                attempt++
                ready(session)
            }
        }
        val newConnection = object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
                val session = runCatching { client.newSession(callback) }.getOrNull()
                main.post { handOut(session) }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                main.post { sheet = null }
            }
        }
        val bound = runCatching { CustomTabsClient.bindCustomTabsService(getApplication(), browser, newConnection) }.getOrDefault(false)
        if (!bound) return handOut(null)
        connection = newConnection
        main.postDelayed({ handOut(null) }, CONNECT_TIMEOUT_MS)
    }

    fun opened() {
        showing = true
    }

    /**
     * Drops a tap still waiting for its session, for a newer tap or a screen that is gone, and the
     * binding it made. An open tab keeps its binding, so its sheet is still reported after a
     * rotation.
     */
    fun dropWaiting() {
        attempt++
        main.removeCallbacksAndMessages(null)
        if (!showing) unbind()
    }

    fun closed() {
        attempt++
        main.removeCallbacksAndMessages(null)
        showing = false
        sheet = null
        unbind()
    }

    private fun unbind() {
        connection?.let { runCatching { getApplication<Application>().unbindService(it) } }
        connection = null
    }

    override fun onCleared() = closed()
}

/** The browser's sheet, at the bottom or at a [side], with its edges in px of the screen. */
data class TabSheet(val left: Int, val top: Int, val right: Int, val side: Boolean) {
    /**
     * How much of a window of [width] by [height] px the sheet covers from each edge. A side sheet
     * sits on the right, or on the left when the browser runs right to left, whatever language
     * Chatter is in.
     */
    fun covered(width: Int, height: Int): SheetCover = when {
        !side -> SheetCover(bottom = (height - top).coerceIn(0, height))
        left <= 0 -> SheetCover(left = right.coerceIn(0, width))
        else -> SheetCover(right = (width - left).coerceIn(0, width))
    }

    companion object {
        fun of(left: Int, top: Int, right: Int, state: Int): TabSheet? = when (state) {
            CustomTabsCallback.ACTIVITY_LAYOUT_STATE_BOTTOM_SHEET -> TabSheet(left, top, right, side = false)
            CustomTabsCallback.ACTIVITY_LAYOUT_STATE_SIDE_SHEET -> TabSheet(left, top, right, side = true)
            // Maximised or full screen, nothing of the chat would be left to show.
            else -> null
        }
    }
}

/** Px covered from the left, right and bottom edge of the window. */
data class SheetCover(val left: Int = 0, val right: Int = 0, val bottom: Int = 0)

private const val TOOLBAR_CORNER_DP = 16

/** How long a tap waits for the browser's session before the tab opens without one. */
private const val CONNECT_TIMEOUT_MS = 800L
private const val TAG = "LinkOpener"
