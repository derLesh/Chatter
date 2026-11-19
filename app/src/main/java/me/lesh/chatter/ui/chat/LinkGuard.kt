package me.lesh.chatter.ui.chat

import android.content.ClipData
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.lesh.chatter.R
import me.lesh.chatter.chat.LinkText
import me.lesh.chatter.debug.AppLog
import kotlinx.coroutines.launch

/**
 * Asks before opening a link disguised as another site (see [LinkText.isUnusual]) and shows where
 * it really goes; other links open directly. Replaces [LocalUriHandler] around [content], so every
 * link in the app passes through it.
 */
@Composable
fun LinkGuard(content: @Composable () -> Unit) {
    val platform = LocalUriHandler.current
    val context = LocalContext.current
    var asking by remember { mutableStateOf<String?>(null) }
    // Android's handler throws when no app takes the link; a toast says so instead of a crash.
    val open = remember(platform, context) {
        { uri: String ->
            runCatching { platform.openUri(uri) }.onFailure {
                AppLog.w("Link", "No app opens a link", it)
                Toast.makeText(context, R.string.link_cannot_open, Toast.LENGTH_SHORT).show()
            }
            Unit
        }
    }
    val guard = remember(open) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (LinkText.isUnusual(uri)) asking = uri else open(uri)
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
                    open(uri)
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
