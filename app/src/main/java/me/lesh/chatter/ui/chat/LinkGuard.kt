package me.lesh.chatter.ui.chat

import android.content.ClipData
import android.view.View
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.lesh.chatter.R
import me.lesh.chatter.chat.LinkText
import me.lesh.chatter.settings.Settings
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.launch

/**
 * Asks before opening a link disguised as another site (see [LinkText.isUnusual]) and shows where
 * it really goes; other links open directly, over the app when [Settings.linksInApp] (see
 * [rememberLinkOpener]). Replaces [LocalUriHandler] around [content], so every link in the app
 * passes through it, and moves [content] up out of the way of a link's sheet when
 * [Settings.chatAboveLinks].
 */
@Composable
fun LinkGuard(settings: Settings, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val tabs = remember(context) { LinkTabs(context.applicationContext) }
    val platform = rememberLinkOpener(settings, tabs)
    var asking by remember { mutableStateOf<String?>(null) }
    val guard = remember(platform) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (LinkText.isUnusual(uri)) asking = uri else platform.openUri(uri)
            }
        }
    }
    val density = LocalDensity.current
    KeepNavigationBarWhile(tabs.showing)
    val window = LocalWindowInfo.current.containerSize
    val sheet = tabs.sheet.takeIf { settings.linksInApp && settings.chatAboveLinks && !settings.linksFullScreen }
    val coveredBottom by animateDpAsState(with(density) { sheet?.top?.let { (window.height - it).coerceAtLeast(0) }?.toDp() ?: 0.dp })
    val coveredEnd by animateDpAsState(with(density) { sheet?.left?.let { (window.width - it).coerceAtLeast(0) }?.toDp() ?: 0.dp })
    val covered = PaddingValues(bottom = coveredBottom, end = coveredEnd)
    CompositionLocalProvider(LocalUriHandler provides guard, LocalLinkTabShowing provides tabs.showing) {
        // The sheet already covers the navigation bar, so what is under it counts as its insets.
        Box(Modifier.fillMaxSize().padding(covered).consumeWindowInsets(covered)) { content() }
    }

    asking?.let { uri ->
        val clipboard = LocalClipboard.current
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(stringResource(R.string.link_check_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.link_check_text))
                    Text(LinkText.honest(uri), fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    runCatching { platform.openUri(uri) }
                }) { Text(stringResource(R.string.open)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    asking = null
                    val text = LinkText.honest(uri)
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(text, text))) }
                }) { Text(stringResource(R.string.action_copy)) }
            },
        )
    }
}

/**
 * True while a link's tab shows over the app. The chat behind it takes no taps, so screens hide
 * their input meanwhile.
 */
val LocalLinkTabShowing = compositionLocalOf { false }

/**
 * Keeps the navigation bar's inset while [active]. Grabbing a link's sheet takes it away for a
 * moment, as if the bar were gone, and the chat would jump down and back up again.
 */
@Composable
private fun KeepNavigationBarWhile(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, active) {
        val content = view.rootView.findViewById<View>(android.R.id.content)
        if (!active || content == null) return@DisposableEffect onDispose {}
        var kept = ViewCompat.getRootWindowInsets(content)?.getInsets(WindowInsetsCompat.Type.navigationBars())
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            val bar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val keep = kept
            if (bar == Insets.NONE && keep != null && keep != Insets.NONE) {
                WindowInsetsCompat.Builder(insets).setInsets(WindowInsetsCompat.Type.navigationBars(), keep).build()
            } else {
                kept = bar
                insets
            }
        }
        onDispose {
            ViewCompat.setOnApplyWindowInsetsListener(content, null)
            content.requestApplyInsets()
        }
    }
}
