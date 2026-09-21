package dev.chatter.app.ui.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.chatter.app.R

/** Twitch's own help on what reporting does and how it is handled. */
private const val REPORT_HELP_URL = "https://link.twitch.tv/HowToFileUserReport"

/**
 * Reporting a chatter. Chatter only displays Twitch's chat, so nothing here can take a message
 * down — the report itself belongs to Twitch, and Twitch has no report URL to link straight to:
 * the form lives behind the "..." menu on a channel page. So this does the two things it can:
 * blocks the user right away, which is the part that takes effect immediately, and hands the
 * message over on the clipboard so it can be pasted into Twitch's form on the page it opens.
 */
@Composable
fun ReportDialog(
    displayName: String,
    login: String,
    message: String,
    alreadyBlocked: Boolean,
    onBlock: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var alsoBlock by remember { mutableStateOf(!alreadyBlocked) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.report_title, displayName)) },
        text = {
            Column {
                Text(stringResource(R.string.report_body, displayName))
                if (!alreadyBlocked) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { alsoBlock = !alsoBlock }
                            .padding(vertical = 4.dp),
                    ) {
                        Checkbox(checked = alsoBlock, onCheckedChange = { alsoBlock = it })
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.report_also_block), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.report_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPORT_HELP_URL)))
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (alsoBlock && !alreadyBlocked) onBlock()
                if (message.isNotBlank()) clipboard.setText(AnnotatedString(message))
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.twitch.tv/$login")))
                onDismiss()
            }) {
                Text(stringResource(R.string.report_open_twitch))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
