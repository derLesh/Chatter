package dev.chatter.app.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import dev.chatter.app.R
import dev.chatter.app.badges.Badge
import dev.chatter.app.channels.ChannelGroup
import dev.chatter.app.channels.ChannelInfo
import dev.chatter.app.channels.displayName
import dev.chatter.app.chat.ChatRole
import dev.chatter.app.chat.RoomState
import dev.chatter.app.irc.ConnectionState
import dev.chatter.app.ui.theme.LiveRed
import dev.chatter.app.ui.theme.barColor

/**
 * The bar above the chat: the current page with a menu of all pages, other channels with news, and
 * buttons for the inbox and settings. [pages] are channels and combined chats in the user's order;
 * [active] is one of them.
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
    /** The user's role in [active], shown in its color under the name. */
    role: ChatRole?,
    /** Shared Chat partners of [active], or null if it shares with nobody. */
    sharedWith: List<String>?,
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
    settingsBadge: Boolean = false,
    /** Pages with an unsent draft. */
    drafts: Set<String> = emptySet(),
) {
    var expanded by remember { mutableStateOf(false) }
    val activeGroup = active?.let { groups[it] }
    // The title's position, to center the full-width menu below it.
    var anchorX by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.barColor),
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
                            ChannelStatus(connection, roomState, role, sharedWith)
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
                    drafts = drafts,
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
            // The unread counts arrive as a new map on every publish; this list must not be rebuilt
            // each time.
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
                // The dot is the only hint out here that an update is waiting.
                BadgedBox(badge = { if (settingsBadge) Badge() }) {
                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                }
            }
        },
    )
}

/**
 * Other channels with something new, as tappable avatars: a red count for mentions, a dot for
 * messages. Caught-up channels are hidden.
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
                            .border(1.5.dp, MaterialTheme.colorScheme.barColor, CircleShape),
                    )
                }
            }
        }
    }
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
    drafts: Set<String>,
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
    // The window's width, not the screen's: they differ in split screen and on foldables.
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } - DROPDOWN_MARGIN * 2
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        // The menu is wider than the space right of the title; offset back to the title's inset for
        // even margins.
        offset = DpOffset(DROPDOWN_MARGIN - anchorX, 0.dp),
        modifier = Modifier.width(width),
    ) {
        pages.forEachIndexed { index, page ->
            val group = groups[page]
            // A combined chat's channels are unknown for a moment after start.
            if (ChannelGroup.isKey(page) && group == null) return@forEachIndexed
            val i = info[page]
            // A combined chat's news is the sum of its channels'.
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
                        if (page in drafts) {
                            DraftMark()
                            Spacer(Modifier.width(6.dp))
                        }
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
        // Combining needs at least two channels.
        if (pages.count { !ChannelGroup.isKey(it) } >= 2) DropdownMenuItem(
            leadingIcon = { Icon(painterResource(R.drawable.ic_combine_chats), contentDescription = null) },
            text = { Text(stringResource(R.string.combine_channels)) },
            onClick = onCombine,
        )
    }
}

/** Margin on each side of the full-width channel menu. */
private val DROPDOWN_MARGIN = 8.dp
