package dev.chatter.app.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import dev.chatter.app.R
import dev.chatter.app.channels.ChannelInfo
import dev.chatter.app.irc.ConnectionState
import dev.chatter.app.ui.theme.LiveRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelTopBar(
    channels: List<String>,
    active: String?,
    info: Map<String, ChannelInfo>,
    unread: Map<String, Int>,
    unreadMessages: Map<String, Int>,
    connection: ConnectionState,
    imageLoader: ImageLoader,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val otherUnread = unread.filterKeys { it != active }.values.sum()

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        title = {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { expanded = true }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    if (active != null) {
                        val i = info[active]
                        ChannelAvatar(i, imageLoader, 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f, fill = false)) {
                            Text(i?.displayName ?: active, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            ChannelStatus(i, connection)
                        }
                    } else {
                        Text(stringResource(R.string.no_channels_title), style = MaterialTheme.typography.titleMedium)
                    }
                    Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.channels))
                    if (otherUnread > 0) Badge { Text(otherUnread.toString()) }
                }
                ChannelDropdown(
                    expanded = expanded,
                    channels = channels,
                    active = active,
                    info = info,
                    unread = unread,
                    unreadMessages = unreadMessages,
                    imageLoader = imageLoader,
                    onDismiss = { expanded = false },
                    onSelect = { expanded = false; onSelect(it) },
                    onAdd = { expanded = false; onAdd() },
                    onRemove = onRemove,
                    onMove = onMove,
                )
            }
        },
        actions = {
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
            }
        },
    )
}

@Composable
private fun ChannelStatus(info: ChannelInfo?, connection: ConnectionState) {
    val text = when {
        connection != ConnectionState.Connected -> stringResource(R.string.status_connecting)
        info?.isLive == true -> stringResource(R.string.status_live, formatViewers(info.viewers))
        else -> stringResource(R.string.status_offline)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = if (info?.isLive == true && connection == ConnectionState.Connected) LiveRed else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

@Composable
private fun ChannelDropdown(
    expanded: Boolean,
    channels: List<String>,
    active: String?,
    info: Map<String, ChannelInfo>,
    unread: Map<String, Int>,
    unreadMessages: Map<String, Int>,
    imageLoader: ImageLoader,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onMove: (String, Int) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        channels.forEachIndexed { index, login ->
            val i = info[login]
            var menu by remember { mutableStateOf(false) }
            DropdownMenuItem(
                leadingIcon = { ChannelAvatar(i, imageLoader, 36.dp) },
                text = {
                    Column {
                        Text(
                            text = i?.displayName ?: login,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (login == active) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            maxLines = 1,
                        )
                        Text(
                            text = if (i?.isLive == true) listOf(stringResource(R.string.status_live, formatViewers(i.viewers)), i.game).filter { it.isNotEmpty() }.joinToString(" \u00B7 ")
                            else stringResource(R.string.status_offline),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (i?.isLive == true) LiveRed else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // New messages (neutral) and mentions (red) since the channel was last viewed.
                        unreadMessages[login]?.takeIf { login != active }?.let { count ->
                            Badge(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ) { Text(formatCount(count)) }
                        }
                        unread[login]?.let {
                            Spacer(Modifier.width(4.dp))
                            Badge { Text("@" + formatCount(it)) }
                        }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.channel_options))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (index > 0) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.move_up)) },
                                    leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null) },
                                    onClick = { menu = false; onMove(login, -1) },
                                )
                                if (index < channels.lastIndex) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.move_down)) },
                                    leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) },
                                    onClick = { menu = false; onMove(login, 1) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.remove_channel)) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                                    onClick = { menu = false; onRemove(login) },
                                )
                            }
                        }
                    }
                },
                onClick = { onSelect(login) },
            )
        }
        if (channels.isNotEmpty()) HorizontalDivider()
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.add_channel)) },
            onClick = onAdd,
        )
    }
}

/** Round profile picture with a red ring and dot while the channel is live. */
@Composable
fun ChannelAvatar(info: ChannelInfo?, imageLoader: ImageLoader, size: Dp) {
    Box(Modifier.size(size)) {
        AsyncImage(
            model = info?.avatarUrl,
            contentDescription = null,
            imageLoader = imageLoader,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .then(if (info?.isLive == true) Modifier.border(2.dp, LiveRed, CircleShape) else Modifier),
        )
        if (info?.isLive == true) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(size / 3.5f)
                    .clip(CircleShape)
                    .background(LiveRed)
                    .border(1.5.dp, MaterialTheme.colorScheme.surfaceContainer, CircleShape),
            )
        }
    }
}

private fun formatCount(n: Int): String = if (n > 999) "999+" else n.toString()

fun formatViewers(n: Int): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.getDefault(), "%.1fM", n / 1_000_000f)
    n >= 1_000 -> String.format(java.util.Locale.getDefault(), "%.1fK", n / 1_000f)
    else -> n.toString()
}
