package dev.chatter.app.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import dev.chatter.app.ui.theme.LiveRed

/**
 * The bar above the chat: the page on screen with the menu of all of them behind it, the other
 * channels that have something new, and the way to the inbox and the settings.
 *
 * [pages] are channels and combined chats in the user's order; [active] is one of them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelTopBar(
    pages: List<String>,
    groups: Map<String, ChannelGroup>,
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
    onCombine: () -> Unit,
    onEditGroup: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRename: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onInbox: () -> Unit,
    inboxUnread: Int,
    onSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val activeGroup = active?.let { groups[it] }
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
                    if (activeGroup != null) {
                        GroupAvatar(activeGroup.channels, info, imageLoader, 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f, fill = false)) {
                            Text(
                                activeGroup.displayName(info),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            GroupStatus(connection, activeGroup, info)
                        }
                    } else if (active != null) {
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
                    pages = pages,
                    groups = groups,
                    active = active,
                    info = info,
                    unread = unread,
                    unreadMessages = unreadMessages,
                    imageLoader = imageLoader,
                    anchorX = anchorX,
                    onDismiss = { expanded = false },
                    onSelect = { expanded = false; onSelect(it) },
                    onAdd = { expanded = false; onAdd() },
                    onCombine = { expanded = false; onCombine() },
                    onEditGroup = { expanded = false; onEditGroup(it) },
                    onRemove = onRemove,
                    onRename = { expanded = false; onRename(it) },
                    onMove = onMove,
                )
            }
        },
        actions = {
            // The unread counts arrive as a fresh map on every publish, so the bar runs again
            // with every message in a channel off screen; this list must not be rebuilt each time.
            val withUnread = remember(pages, hiddenUnread) { pages.filterNot(ChannelGroup::isKey) - hiddenUnread }
            if (showUnread) UnreadStrip(
                channels = withUnread,
                shown = activeGroup?.channels ?: listOfNotNull(active),
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
    shown: List<String>,
    info: Map<String, ChannelInfo>,
    unread: Map<String, Int>,
    unreadMessages: Map<String, Int>,
    imageLoader: ImageLoader,
    onSelect: (String) -> Unit,
) {
    val pending = channels.filter { it !in shown && ((unread[it] ?: 0) > 0 || (unreadMessages[it] ?: 0) > 0) }
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
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Who the user is here comes first, then what the chat is set to. Both are background
        // information under the name the user came for, so they are written the way a subtitle
        // is rather than as chips that compete with it for attention.
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

/**
 * What is under a combined chat's name: that the connection is down, or — when the user gave it a
 * name of its own, which hides them — the channels it reads.
 */
@Composable
private fun GroupStatus(connection: ConnectionState, group: ChannelGroup, info: Map<String, ChannelInfo>) {
    val text = when {
        connection != ConnectionState.Connected -> stringResource(R.string.status_connecting)
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

@Composable
private fun ChannelDropdown(
    expanded: Boolean,
    pages: List<String>,
    groups: Map<String, ChannelGroup>,
    active: String?,
    info: Map<String, ChannelInfo>,
    unread: Map<String, Int>,
    unreadMessages: Map<String, Int>,
    imageLoader: ImageLoader,
    anchorX: Dp,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onCombine: () -> Unit,
    onEditGroup: (String) -> Unit,
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
        pages.forEachIndexed { index, page ->
            val group = groups[page]
            // A combined chat's channels are not known for a moment after start; until they are,
            // there is nothing to show for it.
            if (ChannelGroup.isKey(page) && group == null) return@forEachIndexed
            val i = info[page]
            // What a combined chat has new is what its channels have new, added up.
            val members = group?.channels ?: listOf(page)
            val messages = members.sumOf { unreadMessages[it] ?: 0 }
            val mentions = members.sumOf { unread[it] ?: 0 }
            var menu by remember { mutableStateOf(false) }
            DropdownMenuItem(
                leadingIcon = {
                    if (group != null) GroupAvatar(group.channels, info, imageLoader, 36.dp)
                    else ChannelAvatar(i, imageLoader, 36.dp)
                },
                text = {
                    Column {
                        Text(
                            text = group?.displayName(info) ?: i?.displayName ?: page,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (page == active) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (group != null) {
                            val live = group.channels.count { info[it]?.isLive == true }
                            if (live > 0) Text(
                                text = pluralStringResource(R.plurals.combined_chat_live, live, live),
                                style = MaterialTheme.typography.labelSmall,
                                color = LiveRed,
                                maxLines = 1,
                            )
                        } else if (i?.isLive == true) Text(
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
                        // New messages (neutral) and mentions (red) since the page was last viewed.
                        if (messages > 0 && page != active) {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ) { Text(formatCount(messages)) }
                        }
                        if (mentions > 0) {
                            Spacer(Modifier.width(4.dp))
                            Badge { Text("@" + formatCount(mentions)) }
                        }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.channel_options))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (index > 0) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.move_up)) },
                                    leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null) },
                                    onClick = { menu = false; onMove(page, -1) },
                                )
                                if (index < pages.lastIndex) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.move_down)) },
                                    leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) },
                                    onClick = { menu = false; onMove(page, 1) },
                                )
                                if (group != null) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.edit)) },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                                    onClick = { menu = false; onEditGroup(page) },
                                ) else DropdownMenuItem(
                                    text = { Text(stringResource(R.string.rename_channel)) },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                                    onClick = { menu = false; onRename(page) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.remove_channel)) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                                    onClick = { menu = false; onRemove(page) },
                                )
                            }
                        }
                    }
                },
                onClick = { onSelect(page) },
            )
        }
        if (pages.isNotEmpty()) HorizontalDivider()
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.add_channel)) },
            onClick = onAdd,
        )
        // Combining takes two channels; with fewer there would be nothing to pick in the dialog.
        if (pages.count { !ChannelGroup.isKey(it) } >= 2) DropdownMenuItem(
            leadingIcon = { Icon(painterResource(R.drawable.ic_combine_chats), contentDescription = null) },
            text = { Text(stringResource(R.string.combine_channels)) },
            onClick = onCombine,
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

/**
 * The pictures of a combined chat's first two channels, the second one overlapping the first. Two
 * is as many as stay recognizable at the size of a title bar; the name says the rest.
 */
@Composable
fun GroupAvatar(channels: List<String>, info: Map<String, ChannelInfo>, imageLoader: ImageLoader, size: Dp) {
    val part = size * 0.68f
    Box(Modifier.size(size)) {
        channels.getOrNull(0)?.let { first ->
            Box(Modifier.align(Alignment.TopStart)) { ChannelAvatar(info[first], imageLoader, part) }
        }
        channels.getOrNull(1)?.let { second ->
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    // A ring in the colour behind it sets it off from the picture it lies on.
                    .border(2.dp, MaterialTheme.colorScheme.surfaceContainer, CircleShape),
            ) { ChannelAvatar(info[second], imageLoader, part) }
        }
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
