package me.lesh.chatter.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import me.lesh.chatter.R
import me.lesh.chatter.ui.FormSheet

/** Twitch's help on reporting. */
private const val REPORT_HELP_URL = "https://link.twitch.tv/HowToFileUserReport"

/**
 * Reporting a chatter. Reports belong to Twitch, which has no direct report URL (the form is in a
 * channel page's "..." menu). So this blocks the user right away and copies the message to the
 * clipboard for pasting into Twitch's form.
 */
@Composable
fun ReportSheet(
    displayName: String,
    login: String,
    message: String,
    alreadyBlocked: Boolean,
    onBlock: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Through LinkGuard, which says so instead of crashing when no app opens a link.
    val uriHandler = LocalUriHandler.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var alsoBlock by rememberSaveable { mutableStateOf(!alreadyBlocked) }

    FormSheet(
        title = stringResource(R.string.report_title, displayName),
        text = stringResource(R.string.report_body, displayName),
        confirmLabel = stringResource(R.string.report_open_twitch),
        onConfirm = {
            if (alsoBlock && !alreadyBlocked) onBlock()
            if (message.isNotBlank()) clipboard.setText(AnnotatedString(message))
            uriHandler.openUri("https://www.twitch.tv/$login")
        },
        onDismiss = onDismiss,
    ) {
        if (!alreadyBlocked) {
            item(key = "block") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clickable { alsoBlock = !alsoBlock }
                        .padding(vertical = 4.dp),
                ) {
                    Checkbox(checked = alsoBlock, onCheckedChange = { alsoBlock = it })
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.report_also_block), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item(key = "help") {
            Text(
                stringResource(R.string.report_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.padding(top = 8.dp).clickable { uriHandler.openUri(REPORT_HELP_URL) },
            )
        }
    }
}
