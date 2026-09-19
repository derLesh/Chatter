package dev.chatter.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import dev.chatter.app.R
import dev.chatter.app.chat.ChatItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsSheet(
    item: ChatItem,
    onReply: () -> Unit,
    onMention: () -> Unit,
    onDismiss: () -> Unit,
) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(
                text = listOfNotNull(item.displayName, item.text.ifEmpty { item.systemText }).joinToString(": "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (item.canReply && !item.id.startsWith("local-")) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.action_reply)) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                    modifier = Modifier.clickable { onReply(); onDismiss() },
                )
            }
            if (item.login != null) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.action_mention, item.displayName ?: item.login)) },
                    leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
                    modifier = Modifier.clickable { onMention(); onDismiss() },
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.action_copy)) },
                leadingContent = { Icon(Icons.Default.Share, contentDescription = null) },
                modifier = Modifier.clickable {
                    clipboard.setText(AnnotatedString(item.text.ifEmpty { item.systemText.orEmpty() }))
                    onDismiss()
                },
            )
        }
    }
}
