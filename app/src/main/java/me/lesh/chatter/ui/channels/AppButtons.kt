package me.lesh.chatter.ui.channels

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.lesh.chatter.R

/**
 * The inbox and the settings, at the right end of the bar on the right: the chat's own, or the
 * pane's beside it. Without [onInbox] only the settings, for the inbox's own bar.
 */
@Composable
internal fun AppButtons(
    inboxUnread: Int,
    onInbox: (() -> Unit)?,
    onSettings: () -> Unit,
    settingsBadge: Boolean,
) {
    if (onInbox != null) IconButton(onClick = onInbox) {
        BadgedBox(badge = { if (inboxUnread > 0) Badge { Text(formatCount(inboxUnread)) } }) {
            Icon(Icons.Default.MailOutline, contentDescription = stringResource(R.string.inbox_title))
        }
    }
    IconButton(onClick = onSettings) {
        // The dot is the only hint out here that an update is waiting.
        BadgedBox(badge = { if (settingsBadge) Badge() }) {
            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
        }
    }
}
