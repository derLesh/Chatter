package me.lesh.chatter.ui.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.UriHandler
import androidx.core.net.toUri
import me.lesh.chatter.ui.theme.barColor
import me.lesh.chatter.chat.PostLinks
import me.lesh.chatter.chat.PostSite
import me.lesh.chatter.debug.AppLog

/**
 * Opens web links over the app as a Custom Tab of the user's own browser, so they stay in Chatter.
 * The browser loads the page with the user's logins and blockers, and Chatter never sees it, which
 * an own WebView could not promise. A link an installed app handles still goes to that app; for
 * the sites that get a card (X, Bluesky, YouTube, Twitch: [PostSite]) only when [postsInTheirApp]. A
 * browser without Custom Tabs gets the link the usual way.
 */
@Composable
fun rememberLinkOpener(inApp: Boolean, postsInTheirApp: Boolean): UriHandler {
    val platform = LocalUriHandler.current
    val context = LocalContext.current
    // Partial Custom Tabs only open as a sheet when started for a result: the browser checks which
    // app is calling.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val colors = MaterialTheme.colorScheme
    val window = LocalWindowInfo.current.containerSize
    val look by rememberUpdatedState(
        TabLook(colors.barColor.toArgb(), colors.background.luminance() < 0.5f, window.width, window.height),
    )
    return remember(inApp, postsInTheirApp, platform, context, launcher) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val link = lowercaseScheme(uri)
                if (inApp && isWebLink(link)) {
                    val target = link.toUri()
                    val toApp = postsInTheirApp || PostLinks.find(link, PostSite.entries.toSet()) == null
                    if (toApp && openInOwnApp(context, target)) return
                    if (openInTab(context, target, look) { launcher.launch(it) }) return
                }
                // Throws when no app at all takes the link; a tap on it must not close Chatter.
                runCatching { platform.openUri(link) }.onFailure { AppLog.w(TAG, "No app opens this link", it) }
            }
        }
    }
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

private class TabLook(val toolbarColor: Int, val dark: Boolean, val windowWidthPx: Int, val windowHeightPx: Int)

/** False when the default browser cannot show [target] as a Custom Tab. */
private fun openInTab(context: Context, target: Uri, look: TabLook, launch: (Intent) -> Unit): Boolean {
    // Only the default browser: another one would surprise with other logins and another look.
    val browser = CustomTabsClient.getPackageName(context, emptyList()) ?: return false
    val colors = CustomTabColorSchemeParams.Builder()
        .setToolbarColor(look.toolbarColor)
        .setNavigationBarColor(look.toolbarColor)
        .build()
    val tab = CustomTabsIntent.Builder()
        .setDefaultColorSchemeParams(colors)
        .setColorScheme(if (look.dark) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT)
        .setShowTitle(true)
        .setShareState(CustomTabsIntent.SHARE_STATE_ON)
        // A sheet over the lower part keeps the chat above it in view and usable; dragged up, it
        // takes the whole screen. On wide screens it opens as a side sheet instead.
        .setInitialActivityHeightPx(look.windowHeightPx * 2 / 3, CustomTabsIntent.ACTIVITY_HEIGHT_ADJUSTABLE)
        .setInitialActivityWidthPx(look.windowWidthPx * 45 / 100)
        .setToolbarCornerRadiusDp(TOOLBAR_CORNER_DP)
        .build()
    tab.intent.setPackage(browser).setData(target)
    return try {
        launch(tab.intent)
        true
    } catch (_: ActivityNotFoundException) {
        AppLog.w(TAG, "No Custom Tab in $browser")
        false
    }
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

private const val TOOLBAR_CORNER_DP = 16
private const val TAG = "LinkOpener"
