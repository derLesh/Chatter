package me.lesh.chatter.ui.channels

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import me.lesh.chatter.R
import me.lesh.chatter.badges.Badge
import me.lesh.chatter.channels.ChannelGroup
import me.lesh.chatter.channels.ChannelInfo
import me.lesh.chatter.channels.displayName
import me.lesh.chatter.chat.ChatRole
import me.lesh.chatter.chat.RoomState
import me.lesh.chatter.irc.ConnectionState
import me.lesh.chatter.ui.recap.SettingsGear
import me.lesh.chatter.ui.theme.LiveRed
import me.lesh.chatter.ui.theme.barColor
import me.lesh.chatter.ui.theme.isAppInDarkTheme

/**
 * The bar above the chat in tab mode: every page in swipe order, the add button, inbox and settings
 * in one row. The user sees where a swipe will go before making it.
 *
 * The line under the tabs only shows the role, Shared Chat and chat modes; a channel
 * with none of them has no line. Holding a tab opens the same options as the channel menu.
 */
@Composable
fun ChannelTabBar(
    pages: List<String>,
    groups: Map<String, ChannelGroup>,
    active: String?,
    info: Map<String, ChannelInfo>,
    unread: Map<String, Int>,
    /** Channels with new messages. */
    withNews: Set<String>,
    roomState: RoomState?,
    /** The user's role in [active]; the line under its tab takes the role's color. */
    role: ChatRole?,
    /** Shared Chat partners of [active], or null if it shares with nobody. */
    sharedWith: List<String>?,
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
    /** A tab's menu opened; the place to find out early whether its page will have a Settings tab. */
    onMenuOpen: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    /** Null for a guest, who has no inbox. */
    onInbox: (() -> Unit)?,
    inboxUnread: Int,
    onSettings: () -> Unit,
    settingsBadge: Boolean = false,
    /** Confetti on the gear: a look back at the year is ready. */
    celebrate: Boolean = false,
    /** Pages with an unsent draft. */
    drafts: Set<String> = emptySet(),
) {
    val activeGroup = active?.let { groups[it] }
    Surface(color = MaterialTheme.colorScheme.barColor) {
        Column(Modifier.windowInsetsPadding(TopAppBarDefaults.windowInsets)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelTabs(
                    pages = pages,
                    groups = groups,
                    active = active,
                    info = info,
                    unread = unread,
                    withNews = withNews,
                    showUnread = showUnread,
                    hiddenUnread = hiddenUnread,
                    drafts = drafts,
                    role = role,
                    imageLoader = imageLoader,
                    onSelect = onSelect,
                    onAdd = onAdd,
                    onCombine = onCombine,
                    onEditGroup = onEditGroup,
                    onRemove = onRemove,
                    onRename = onRename,
                    onMove = onMove,
                    onMenuOpen = onMenuOpen,
                    onOpenChannel = onOpenChannel,
                    modifier = Modifier.weight(1f),
                )
                if (onInbox != null) {
                    IconButton(onClick = onInbox) {
                        BadgedBox(badge = { if (inboxUnread > 0) Badge { Text(formatCount(inboxUnread)) } }) {
                            Icon(Icons.Default.MailOutline, contentDescription = stringResource(R.string.inbox_title))
                        }
                    }
                }
                IconButton(onClick = onSettings) {
                    // The dot is the only hint out here that an update is waiting.
                    SettingsGear(updateDot = settingsBadge, celebrate = celebrate)
                }
            }
            val status = when {
                active == null -> null
                activeGroup != null -> groupStatus(activeGroup, info)
                else -> channelStatus(connection, roomState, roleBadge = null, sharedWith)
            }
            // A little room above, so the line does not stick to the tab underline.
            StatusLineView(status, imageLoader, PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp))
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ChannelTabs(
    pages: List<String>,
    groups: Map<String, ChannelGroup>,
    active: String?,
    info: Map<String, ChannelInfo>,
    unread: Map<String, Int>,
    withNews: Set<String>,
    showUnread: Boolean,
    hiddenUnread: Set<String>,
    drafts: Set<String>,
    role: ChatRole?,
    imageLoader: ImageLoader,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onCombine: (String) -> Unit,
    onEditGroup: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRename: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onMenuOpen: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    modifier: Modifier,
) {
    // A combined chat's channels are unknown for a moment after start; its tab would have neither
    // name nor picture.
    val shown = pages.filter { !ChannelGroup.isKey(it) || groups[it] != null }
    val selected = shown.indexOf(active)
    // Right after the active page is removed, active still names it for a frame. The row scrolls
    // whenever its index changes, so falling back to the first tab would leave it at the start.
    val lastSelected = remember { SelectedTab() }
    if (selected >= 0) lastSelected.index = selected
    val channelCount = pages.count { !ChannelGroup.isKey(it) }
    // Moving a tab slides it and its neighbour into their new places instead of jumping there.
    LookaheadScope {
        val lookahead = this
        PrimaryScrollableTabRow(
            // The row cannot show "no tab selected" (briefly after start, after a removal, or with no
            // channels); it draws no indicator then.
            selectedTabIndex = lastSelected.index.coerceIn(0, shown.lastIndex.coerceAtLeast(0)),
            containerColor = MaterialTheme.colorScheme.barColor,
            edgePadding = 4.dp,
            minTabWidth = 0.dp,
            indicator = {
                if (selected >= 0) TabRowDefaults.PrimaryIndicator(
                    Modifier.tabIndicatorOffset(selected, matchContentSize = false).padding(horizontal = 12.dp),
                    width = Dp.Unspecified,
                    color = role?.color ?: MaterialTheme.colorScheme.primary,
                )
            },
            divider = {},
            modifier = modifier,
        ) {
            shown.forEachIndexed { index, page ->
                // Keyed by the page, so a tab's open menu and its slide stay with it when it moves.
                key(page) {
                    val group = groups[page]
                    val members = (group?.channels ?: listOf(page)).filter { it !in hiddenUnread }
                    val isSelected = index == selected
                    // The page being read has nothing new, whatever the count says before it is cleared.
                    val mentions = if (showUnread && !isSelected) members.sumOf { unread[it] ?: 0 } else 0
                    val hasNew = showUnread && !isSelected && members.any { it in withNews }
                    val position = pages.indexOf(page)
                    ChannelTab(
                        name = group?.displayName(info) ?: info[page]?.displayName ?: page,
                        avatar = { size ->
                            if (group != null) GroupAvatar(group.channels, info, imageLoader, size)
                            else ChannelAvatar(info[page], imageLoader, size)
                        },
                        selected = isSelected,
                        // Said aloud for the tab whose underline shows it.
                        roleName = role?.label?.takeIf { isSelected }?.let { stringResource(it) },
                        mentions = mentions,
                        hasNew = hasNew,
                        hasDraft = page in drafts,
                        onClick = { onSelect(page) },
                        onMenuOpen = { onMenuOpen(page) },
                        onOpenChannel = { onOpenChannel(page) },
                        status = pageStatus(group, info[page], info),
                        onMoveLeft = { onMove(page, -1) }.takeIf { position > 0 },
                        onMoveRight = { onMove(page, 1) }.takeIf { position < pages.lastIndex },
                        actions = if (group != null) listOf(
                            TabAction(stringResource(R.string.edit), { Icon(Icons.Default.Edit, null) }) { onEditGroup(page) },
                        ) else listOfNotNull(
                            // Combining needs at least two channels.
                            TabAction(stringResource(R.string.combine_channels), { Icon(painterResource(R.drawable.ic_combine_chats), null) }) {
                                onCombine(page)
                            }.takeIf { channelCount >= 2 },
                            TabAction(stringResource(R.string.rename_channel), { Icon(Icons.Default.Edit, null) }) { onRename(page) },
                        ),
                        onRemove = { onRemove(page) },
                        modifier = Modifier.animateBounds(lookahead),
                    )
                }
            }
            // At the end, where the new channel will appear.
            IconButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_channel))
            }
        }
    }
}

/** Not state: the row only needs it on the next composition, and reading it must not cause one. */
private class SelectedTab(var index: Int = 0)

/** One entry of a held tab's menu. */
private class TabAction(val label: String, val icon: @Composable () -> Unit, val onClick: () -> Unit)

/**
 * A page's live line under its name in the menu: a channel's viewers and category, or how many
 * of a combined chat's channels are live; null while nothing is.
 */
@Composable
private fun pageStatus(group: ChannelGroup?, channel: ChannelInfo?, info: Map<String, ChannelInfo>): String? {
    if (group != null) {
        val live = group.channels.count { info[it]?.isLive == true }
        return if (live > 0) pluralStringResource(R.plurals.combined_chat_live, live, live) else null
    }
    if (channel?.isLive != true) return null
    return listOf(stringResource(R.string.status_live, formatViewers(channel.viewers)), channel.game).filter { it.isNotEmpty() }.joinToString(" · ")
}

/**
 * One page as a tab. Not Material's Tab, which cannot be held. Holding opens the page's menu: the
 * page itself as its header, which opens its channel page, then moving it, [actions] and removing.
 * [onMoveLeft] and [onMoveRight] are null at either end.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelTab(
    name: String,
    avatar: @Composable (Dp) -> Unit,
    selected: Boolean,
    roleName: String?,
    mentions: Int,
    hasNew: Boolean,
    hasDraft: Boolean,
    onClick: () -> Unit,
    onMenuOpen: () -> Unit,
    onOpenChannel: () -> Unit,
    status: String?,
    onMoveLeft: (() -> Unit)?,
    onMoveRight: (() -> Unit)?,
    actions: List<TabAction>,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    // Quiet tabs step well back on a dark theme, so a tab with new messages stands out even when
    // the accent is white and the dot has no color of its own; on a light one that grey would be
    // too faint to read.
    val color = when {
        selected -> MaterialTheme.colorScheme.primary
        hasNew || mentions > 0 -> MaterialTheme.colorScheme.onSurface
        isAppInDarkTheme() -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(56.dp)
            .semantics {
                role = Role.Tab
                this.selected = selected
                roleName?.let { stateDescription = it }
            }
            .combinedClickable(
                onLongClickLabel = stringResource(R.string.channel_options),
                onLongClick = {
                    open = true
                    onMenuOpen()
                },
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides color) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                avatar(24.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    // Bold together with the dot marks new messages.
                    fontWeight = if (hasNew || mentions > 0) FontWeight.Bold else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
                if (hasDraft) {
                    Spacer(Modifier.width(4.dp))
                    DraftMark()
                }
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
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                Column(Modifier.width(MENU_WIDTH)) {
                    MenuHeader(name, status, avatar) { open = false; onOpenChannel() }
                    if (onMoveLeft != null || onMoveRight != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 10.dp).padding(bottom = 10.dp)) {
                            // Both stay, one greyed out at an end, so the arrows keep their places.
                            MoveButton(stringResource(R.string.move_left), Icons.AutoMirrored.Filled.KeyboardArrowLeft, onMoveLeft, Modifier.weight(1f))
                            MoveButton(stringResource(R.string.move_right), Icons.AutoMirrored.Filled.KeyboardArrowRight, onMoveRight, Modifier.weight(1f))
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    actions.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(action.label) },
                            leadingIcon = action.icon,
                            onClick = { open = false; action.onClick() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remove_channel)) },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        colors = MenuDefaults.itemColors(
                            textColor = MaterialTheme.colorScheme.error,
                            leadingIconColor = MaterialTheme.colorScheme.error,
                        ),
                        onClick = { open = false; onRemove() },
                    )
                }
            }
        }
    }
}

/** The page at the top of its menu: picture, name and live line, and an arrow to its channel page. */
@Composable
private fun MenuHeader(name: String, status: String?, avatar: @Composable (Dp) -> Unit, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.channel_open), onClick = onOpen)
            .padding(14.dp),
    ) {
        avatar(44.dp)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (status != null) {
                Text(status, style = MaterialTheme.typography.labelMedium, color = LiveRed, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One of the two arrows that move a tab, greyed out without [onClick]. */
@Composable
private fun MoveButton(label: String, icon: ImageVector, onClick: (() -> Unit)?, modifier: Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(enabled = onClick != null, onClickLabel = label) { onClick?.invoke() },
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (onClick != null) 1f else 0.38f),
        )
    }
}

/** Wide enough for the name and live line of a channel, like a menu with a header. */
private val MENU_WIDTH = 260.dp
