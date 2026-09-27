package dev.chatter.app.ui.channels

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import dev.chatter.app.R
import dev.chatter.app.badges.Badge
import dev.chatter.app.channels.ChannelGroup
import dev.chatter.app.channels.ChannelInfo
import dev.chatter.app.channels.displayName
import dev.chatter.app.chat.RoomState
import dev.chatter.app.irc.ConnectionState

/**
 * The bar above the chat when the pages are tabs: every page in the order a swipe goes through
 * them, the way to add one, the inbox and the settings — all in one row. A swipe lands on the tab
 * next to the marked one, so the user can see where it is going before they make it, which a
 * title naming only the page on screen cannot say.
 *
 * The tab already names the page, so the line under it carries only what the title's second line
 * did: the connection, the user's role and the chat modes. A channel with none of them has no line.
 *
 * Holding a tab opens what the channel menu of the other bar holds for that page.
 */
@Composable
fun ChannelTabBar(
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
    onCombine: (String) -> Unit,
    onEditGroup: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRename: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onInbox: () -> Unit,
    inboxUnread: Int,
    onSettings: () -> Unit,
) {
    val activeGroup = active?.let { groups[it] }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.windowInsetsPadding(TopAppBarDefaults.windowInsets)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelTabs(
                    pages = pages,
                    groups = groups,
                    active = active,
                    info = info,
                    unread = unread,
                    unreadMessages = unreadMessages,
                    showUnread = showUnread,
                    hiddenUnread = hiddenUnread,
                    imageLoader = imageLoader,
                    onSelect = onSelect,
                    onAdd = onAdd,
                    onCombine = onCombine,
                    onEditGroup = onEditGroup,
                    onRemove = onRemove,
                    onRename = onRename,
                    onMove = onMove,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onInbox) {
                    BadgedBox(badge = { if (inboxUnread > 0) Badge { Text(formatCount(inboxUnread)) } }) {
                        Icon(Icons.Default.MailOutline, contentDescription = stringResource(R.string.inbox_title))
                    }
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                }
            }
            // Asked here rather than left to the status line, which would still take its padding
            // while it has nothing to say.
            val hasStatus = when {
                active == null -> false
                connection != ConnectionState.Connected -> true
                activeGroup != null -> activeGroup.name.isNotBlank()
                else -> roleBadge != null || roomState?.hasModes() == true
            }
            if (hasStatus) Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp)) {
                if (activeGroup != null) GroupStatus(connection, activeGroup, info)
                else ChannelStatus(connection, roomState, roleBadge, imageLoader)
            }
        }
    }
}

private fun RoomState.hasModes(): Boolean =
    slow > 0 || followersOnly >= 0 || subsOnly || emoteOnly || uniqueChat

@Composable
private fun ChannelTabs(
    pages: List<String>,
    groups: Map<String, ChannelGroup>,
    active: String?,
    info: Map<String, ChannelInfo>,
    unread: Map<String, Int>,
    unreadMessages: Map<String, Int>,
    showUnread: Boolean,
    hiddenUnread: Set<String>,
    imageLoader: ImageLoader,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onCombine: (String) -> Unit,
    onEditGroup: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRename: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    modifier: Modifier,
) {
    // A combined chat's channels are not known for a moment after start; a tab for it then would
    // have neither a name nor a picture.
    val shown = pages.filter { !ChannelGroup.isKey(it) || groups[it] != null }
    val selected = shown.indexOf(active)
    val channelCount = pages.count { !ChannelGroup.isKey(it) }
    PrimaryScrollableTabRow(
        // The row cannot be told that no tab is selected, which is the case for a moment after
        // start and whenever there are no channels at all; it then draws no indicator instead.
        selectedTabIndex = selected.coerceAtLeast(0),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        edgePadding = 4.dp,
        minTabWidth = 0.dp,
        indicator = {
            if (selected >= 0) TabRowDefaults.PrimaryIndicator(
                Modifier.tabIndicatorOffset(selected, matchContentSize = false).padding(horizontal = 12.dp),
                width = Dp.Unspecified,
            )
        },
        divider = {},
        modifier = modifier,
    ) {
        shown.forEachIndexed { index, page ->
            val group = groups[page]
            val members = (group?.channels ?: listOf(page)).filter { it !in hiddenUnread }
            val isSelected = index == selected
            // What the user is reading has nothing new by definition, whatever the count still
            // says in the moment before it is cleared.
            val mentions = if (showUnread && !isSelected) members.sumOf { unread[it] ?: 0 } else 0
            val hasNew = showUnread && !isSelected && members.any { (unreadMessages[it] ?: 0) > 0 }
            val position = pages.indexOf(page)
            ChannelTab(
                name = group?.displayName(info) ?: info[page]?.displayName ?: page,
                avatar = {
                    if (group != null) GroupAvatar(group.channels, info, imageLoader, 24.dp)
                    else ChannelAvatar(info[page], imageLoader, 24.dp)
                },
                selected = isSelected,
                mentions = mentions,
                hasNew = hasNew,
                onClick = { onSelect(page) },
                // Grouped by what they do to the tab: where it stands, what it is, and whether it stays.
                actions = listOf(
                    listOfNotNull(
                        TabAction(stringResource(R.string.move_left), { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null) }) {
                            onMove(page, -1)
                        }.takeIf { position > 0 },
                        TabAction(stringResource(R.string.move_right), { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }) {
                            onMove(page, 1)
                        }.takeIf { position < pages.lastIndex },
                    ),
                    if (group != null) listOf(
                        TabAction(stringResource(R.string.edit), { Icon(Icons.Default.Edit, null) }) { onEditGroup(page) },
                    ) else listOfNotNull(
                        // Combining takes two channels; with fewer the dialog has nothing to pick.
                        TabAction(stringResource(R.string.combine_channels), { Icon(painterResource(R.drawable.ic_combine_chats), null) }) {
                            onCombine(page)
                        }.takeIf { channelCount >= 2 },
                        TabAction(stringResource(R.string.rename_channel), { Icon(Icons.Default.Edit, null) }) { onRename(page) },
                    ),
                    listOf(
                        TabAction(stringResource(R.string.remove_channel), { Icon(Icons.Default.Delete, null) }) { onRemove(page) },
                    ),
                ).filter { it.isNotEmpty() },
            )
        }
        // At the end of the row, where the next channel will appear once it is added.
        IconButton(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_channel))
        }
    }
}

/** One entry of the menu a tab opens when it is held. */
private class TabAction(val label: String, val icon: @Composable () -> Unit, val onClick: () -> Unit)

/**
 * One page as a tab. Not Material's own Tab, which has no way to be held: holding is what opens
 * the page's options, [actions], one menu group per list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelTab(
    name: String,
    avatar: @Composable () -> Unit,
    selected: Boolean,
    mentions: Int,
    hasNew: Boolean,
    onClick: () -> Unit,
    actions: List<List<TabAction>>,
) {
    var open by remember { mutableStateOf(false) }
    val color = when {
        selected -> MaterialTheme.colorScheme.primary
        hasNew || mentions > 0 -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(56.dp)
            .semantics {
                role = Role.Tab
                this.selected = selected
            }
            .combinedClickable(
                onLongClickLabel = stringResource(R.string.channel_options),
                onLongClick = { open = true },
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides color) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                avatar()
                Spacer(Modifier.width(8.dp))
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    // Bold is the quiet half of "something is new here"; the dot beside it is the other.
                    fontWeight = if (hasNew || mentions > 0) FontWeight.Bold else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
                if (mentions > 0) {
                    Spacer(Modifier.width(6.dp))
                    Badge { Text(formatCount(mentions)) }
                } else if (hasNew) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }) {
            actions.forEachIndexed { g, group ->
                if (g > 0) Spacer(Modifier.height(MenuDefaults.GroupSpacing))
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(g, actions.size)) {
                    group.forEachIndexed { i, action ->
                        DropdownMenuItem(
                            text = { Text(action.label) },
                            leadingIcon = action.icon,
                            shape = MenuDefaults.itemShape(i, group.size).shape,
                            onClick = { open = false; action.onClick() },
                        )
                    }
                }
            }
        }
    }
}
