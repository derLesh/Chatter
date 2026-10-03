package dev.chatter.app.ui.chat

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.chatter.app.R
import dev.chatter.app.chat.LinkText
import kotlinx.coroutines.launch

/**
 * Asks before opening a link disguised as another site (see [LinkText.isUnusual]) and shows where
 * it really goes; other links open directly. Replaces [LocalUriHandler] around [content], so every
 * link in the app passes through it.
 */
@Composable
fun LinkGuard(content: @Composable () -> Unit) {
    val platform = LocalUriHandler.current
    var asking by remember { mutableStateOf<String?>(null) }
    val guard = remember(platform) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (LinkText.isUnusual(uri)) asking = uri else platform.openUri(uri)
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides guard) { content() }

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
