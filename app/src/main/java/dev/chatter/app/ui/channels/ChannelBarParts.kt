package dev.chatter.app.ui.channels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import dev.chatter.app.R
import dev.chatter.app.badges.Badge
import dev.chatter.app.channels.ChannelGroup
import dev.chatter.app.channels.ChannelInfo
import dev.chatter.app.channels.displayName
import dev.chatter.app.chat.RoomState
import dev.chatter.app.irc.ConnectionState

/** The line under the channel name: the user's role, a running Shared Chat, and the chat modes. */
@Composable
private fun ChannelModes(state: RoomState?, roleBadge: Badge?, sharedWith: List<String>?, imageLoader: ImageLoader) {
    val modes = buildList {
        // First, because it changes whose messages fill the chat.
        if (sharedWith != null) add(
            if (sharedWith.isEmpty()) stringResource(R.string.shared_chat)
            else stringResource(R.string.shared_chat_with, sharedWith.joinToString(", ")),
        )
        if (state == null) return@buildList
        if (state.slow > 0) add(stringResource(R.string.mode_slow, state.slow))
        if (state.followersOnly == 0) add(stringResource(R.string.mode_followers))
        if (state.followersOnly > 0) add(stringResource(R.string.mode_followers_time, formatMinutes(state.followersOnly)))
        if (state.subsOnly) add(stringResource(R.string.mode_subs))
        if (state.emoteOnly) add(stringResource(R.string.mode_emotes))
        if (state.uniqueChat) add(stringResource(R.string.mode_unique))
    }
    // Nothing to show for a normal chatter in an unrestricted channel; an empty row would still
    // take space.
    if (modes.isEmpty() && roleBadge == null) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Role first, then modes, styled as a subtitle rather than chips so they do not compete
        // with the name.
        if (roleBadge != null) {
            AsyncImage(
                model = roleBadge.url,
                contentDescription = roleBadge.title,
                imageLoader = imageLoader,
                modifier = Modifier.size(16.dp),
            )
        }
        if (modes.isNotEmpty()) {
            Text(
                text = modes.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatMinutes(minutes: Int): String = when {
    minutes % 10_080 == 0 -> "${minutes / 10_080}w"
    minutes % 1440 == 0 -> "${minutes / 1440}d"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes}m"
}

@Composable
internal fun ChannelStatus(
    connection: ConnectionState,
    state: RoomState?,
    roleBadge: Badge?,
    sharedWith: List<String>?,
    imageLoader: ImageLoader,
) {
    // A dropped connection takes the line, since it also makes role and modes stale.
    if (connection != ConnectionState.Connected) {
        Text(
            text = stringResource(connectionStatus(connection)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        return
    }
    ChannelModes(state, roleBadge, sharedWith, imageLoader)
}

/** Title bar text while not connected. */
internal fun connectionStatus(connection: ConnectionState): Int =
    if (connection == ConnectionState.WaitingForNetwork) R.string.status_waiting_network else R.string.status_connecting

/**
 * The line under a combined chat's name: a dropped connection, or its channels when it has a custom
 * name.
 */
@Composable
internal fun GroupStatus(connection: ConnectionState, group: ChannelGroup, info: Map<String, ChannelInfo>) {
    val text = when {
        connection != ConnectionState.Connected -> stringResource(connectionStatus(connection))
        group.name.isNotBlank() -> group.channels.joinToString(" \u00B7 ") { info[it]?.displayName ?: it }
        else -> return
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Next to a page's name: it has an unsent draft. */
@Composable
internal fun DraftMark() {
    Icon(
        Icons.Default.Edit,
        contentDescription = stringResource(R.string.draft_waiting),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(14.dp),
    )
}

internal fun formatCount(n: Int): String = if (n > 999) "999+" else n.toString()

fun formatViewers(n: Int): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.getDefault(), "%.1fM", n / 1_000_000f)
    n >= 1_000 -> String.format(java.util.Locale.getDefault(), "%.1fK", n / 1_000f)
    else -> n.toString()
}
