package dev.chatter.app.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import dev.chatter.app.R
import dev.chatter.app.badges.Badge
import dev.chatter.app.channels.ChannelInfo
import dev.chatter.app.chat.RoomState
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
    roomState: RoomState?,
    roleBadge: Badge?,
    connection: ConnectionState,
    showUnread: Boolean,
    hiddenUnread: Set<String>,
    imageLoader: ImageLoader,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onRename: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onInbox: () -> Unit,
    inboxUnread: Int,
    onSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // Where the title sits, so the full-width menu below it can be centered on the screen.
    var anchorX by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        title = {
            Box(Modifier.onGloballyPositioned { anchorX = with(density) { it.positionInWindow().x.toDp() } }) {
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
                            ChannelStatus(connection, roomState, roleBadge, imageLoader)
                        }
                    } else {
                        Text(stringResource(R.string.no_channels_title), style = MaterialTheme.typography.titleMedium)
                    }
                    Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.channels))
                }
                ChannelDropdown(
                    expanded = expanded,
                    channels = channels,
                    active = active,
                    info = info,
                    unread = unread,
                    unreadMessages = unreadMessages,
                    imageLoader = imageLoader,
                    anchorX = anchorX,
                    onDismiss = { expanded = false },
                    onSelect = { expanded = false; onSelect(it) },
                    onAdd = { expanded = false; onAdd() },
                    onRemove = onRemove,
                    onRename = { expanded = false; onRename(it) },
                    onMove = onMove,
                )
            }
        },
        actions = {
            if (showUnread) UnreadStrip(
                channels = channels - hiddenUnread,
                active = active,
                info = info,
                unread = unread,
                unreadMessages = unreadMessages,
                imageLoader = imageLoader,
                onSelect = onSelect,
            )
            IconButton(onClick = onInbox) {
                BadgedBox(
                    badge = { if (inboxUnread > 0) Badge { Text(formatCount(inboxUnread)) } },
                ) {
                    Icon(Icons.Default.MailOutline, contentDescription = stringResource(R.string.inbox_title))
                }
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
            }
        },
    )
}

/**
 * The other channels that have something new, as tappable avatars: a red count for mentions,
 * a plain dot for ordinary messages. Channels the user has caught up on are not shown at all.
 */
@Composable
private fun UnreadStrip(
    channels: List<String>,
    active: String?,
    info: Map<String, ChannelInfo>,
    unread: Map<String, Int>,
    unreadMessages: Map<String, Int>,
    imageLoader: ImageLoader,
    onSelect: (String) -> Unit,
) {
    val pending = channels.filter { it != active && ((unread[it] ?: 0) > 0 || (unreadMessages[it] ?: 0) > 0) }
    if (pending.isEmpty()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.widthIn(max = 132.dp).horizontalScroll(rememberScrollState()),
    ) {
        pending.forEach { login ->
            val mentions = unread[login] ?: 0
            val name = info[login]?.displayName ?: login
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClickLabel = stringResource(R.string.unread_in_channel, name)) { onSelect(login) }
                    .padding(3.dp),
            ) {
                ChannelAvatar(info[login], imageLoader, 26.dp)
                if (mentions > 0) {
                    Badge(Modifier.align(Alignment.TopEnd)) { Text(formatCount(mentions)) }
                } else {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .border(1.5.dp, MaterialTheme.colorScheme.surfaceContainer, CircleShape),
                    )
                }
            }
        }
    }
}

/** The user's role badge and the active chat modes as small chips, next to the settings icon. */
@Composable
private fun ChannelModes(state: RoomState?, roleBadge: Badge?, imageLoader: ImageLoader) {
    val modes = buildList {
        if (state == null) return@buildList
        if (state.slow > 0) add(stringResource(R.string.mode_slow, state.slow))
        if (state.followersOnly == 0) add(stringResource(R.string.mode_followers))
        if (state.followersOnly > 0) add(stringResource(R.string.mode_followers_time, formatMinutes(state.followersOnly)))
        if (state.subsOnly) add(stringResource(R.string.mode_subs))
        if (state.emoteOnly) add(stringResource(R.string.mode_emotes))
        if (state.uniqueChat) add(stringResource(R.string.mode_unique))
    }
    // A normal chatter in an unrestricted channel has nothing to read here, and an empty row
    // under the name would still take the height of one.
    if (modes.isEmpty() && roleBadge == null) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        // Bounded by the title it sits under; the modes scroll sideways when there are many.
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        // Who the user is here comes first, then what the chat is set to.
        if (roleBadge != null) {
            AsyncImage(
                model = roleBadge.url,
                contentDescription = roleBadge.title,
                imageLoader = imageLoader,
                modifier = Modifier.size(18.dp),
            )
        }
        modes.forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
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
private fun ChannelStatus(
    connection: ConnectionState,
    state: RoomState?,
    roleBadge: Badge?,
    imageLoader: ImageLoader,
) {
    // A dropped connection is the one thing worth saying in words; it also makes the role and
    // the modes stale, so it takes the line for itself.
    if (connection != ConnectionState.Connected) {
        Text(
            text = stringResource(R.string.status_connecting),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        return
    }
    ChannelModes(state, roleBadge, imageLoader)
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
    anchorX: Dp,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onRename: (String) -> Unit,
    onMove: (String, Int) -> Unit,
) {
    val width = LocalConfiguration.current.screenWidthDp.dp - DROPDOWN_MARGIN * 2
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        // The menu is wider than the space right of the title, so it would be flushed against the
        // right edge. Pulling it back to the title's own inset leaves an even margin on both sides.
        offset = DpOffset(DROPDOWN_MARGIN - anchorX, 0.dp),
        modifier = Modifier.width(width),
    ) {
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
                        if (i?.isLive == true) Text(
                            text = listOf(stringResource(R.string.status_live, formatViewers(i.viewers)), i.game)
                                .filter { it.isNotEmpty() }.joinToString(" \u00B7 "),
                            style = MaterialTheme.typography.labelSmall,
                            color = LiveRed,
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
                                    text = { Text(stringResource(R.string.rename_channel)) },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                                    onClick = { menu = false; onRename(login) },
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
                // The red ring is the whole of "this channel is live", wherever a picture shows up.
                .then(if (info?.isLive == true) Modifier.border(2.dp, LiveRed, CircleShape) else Modifier),
        )
    }
}

/** Left over on each side once the channel menu is opened up to the full screen width. */
private val DROPDOWN_MARGIN = 8.dp

private fun formatCount(n: Int): String = if (n > 999) "999+" else n.toString()

fun formatViewers(n: Int): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.getDefault(), "%.1fM", n / 1_000_000f)
    n >= 1_000 -> String.format(java.util.Locale.getDefault(), "%.1fK", n / 1_000f)
    else -> n.toString()
}
