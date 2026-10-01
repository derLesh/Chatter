package dev.chatter.app.ui.chat

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.chatter.app.R
import dev.chatter.app.chat.LinkText

/**
 * Asks before a link that is dressed up as another site is opened (see [LinkText.isUnusual]),
 * showing where it really goes. Every other link opens on a tap, as before.
 *
 * It takes the place of [LocalUriHandler] around [content], which is what Compose opens a tapped
 * link in a message with — so every link in the app goes through it, not only the ones a screen
 * remembered to check.
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
        val clipboard = LocalClipboardManager.current
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
                    clipboard.setText(AnnotatedString(LinkText.honest(uri)))
                }) { Text(stringResource(R.string.action_copy)) }
            },
        )
    }
}
