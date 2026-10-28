package dev.chatter.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.ImageLoader
import coil3.compose.AsyncImage
import dev.chatter.app.R
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.net.HelixUser
import dev.chatter.app.ui.theme.readableNameColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Opened from a message: the author (avatar, badges, account age, bio), actions, and their recent
 * messages in this channel. [onWhisper] receives the Twitch id once loaded, saving a lookup. A
 * [guest] gets no actions that write; without [onReply] and [onMention] those two are left out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserCardSheet(
    item: ChatItem,
    canModerate: Boolean,
    guest: Boolean,
    style: ChatStyle,
    imageLoader: ImageLoader,
    recentMessages: suspend () -> List<ChatItem>,
    profile: suspend () -> HelixUser?,
    blocked: Boolean,
    /** Copy first and Reply last in the row, instead of the reverse. */
    copyFirst: Boolean,
    onBlock: (HelixUser, Boolean) -> Unit,
    onBlockLogin: (String) -> Unit,
    onNickname: () -> Unit,
    onReply: (() -> Unit)?,
    onMention: (() -> Unit)?,
    onWhisper: (userId: String?) -> Unit,
    onDelete: () -> Unit,
    /** Seconds; see [TIMEOUTS]. */
    onTimeout: (Int) -> Unit,
    onBan: () -> Unit,
    onDismiss: () -> Unit,
) {
    var recent by remember(item.id) { mutableStateOf<List<ChatItem>?>(null) }
    var user by remember(item.id) { mutableStateOf<HelixUser?>(null) }
    var reporting by remember(item.id) { mutableStateOf(false) }
    var avatarOpen by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id) { recent = recentMessages() }
    LaunchedEffect(item.id) { user = profile() }

    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val isUserMessage = item.login != null
    val canReply = !guest && onReply != null && item.canReply && !item.id.startsWith("local-")

    // Waits for the recent messages, which come from memory. Opened earlier the sheet would be
    // short, expand fully and stay so; opened with them, a long card opens half-way.
    val messages = recent ?: return
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        LazyColumn(Modifier.fillMaxWidth()) {
            if (isUserMessage) {
                item {
                    // Everything about the person that is rarely needed: one tap away in a menu,
                    // with the harsh actions in a group of their own.
                    val uriHandler = LocalUriHandler.current
                    val openIcon = ImageVector.vectorResource(R.drawable.ic_open_in_new)
                    val reportIcon = ImageVector.vectorResource(R.drawable.ic_report_flag)
                    val menu = listOf(
                        listOfNotNull(
                            ButtonAction(R.string.action_whisper, Icons.Default.MailOutline) {
                                onWhisper(user?.id)
                                onDismiss()
                            }.takeIf { !guest && !item.isOwn },
                            ButtonAction(R.string.nickname_title, Icons.Default.Edit, onClick = onNickname),
                            item.login?.let { login ->
                                ButtonAction(R.string.action_open_on_twitch, openIcon) { uriHandler.openUri("https://www.twitch.tv/$login") }
                            },
                        ),
                        if (item.isOwn) emptyList() else listOfNotNull(
                            // Blocking needs the profile's Twitch id; reporting works without it.
                            user?.let { user ->
                                ButtonAction(
                                    label = if (blocked) R.string.action_unblock else R.string.action_block,
                                    icon = if (blocked) Icons.Default.Check else Icons.Default.Clear,
                                    danger = !blocked,
                                ) { onBlock(user, !blocked); onDismiss() }
                            },
                            ButtonAction(R.string.action_report, reportIcon, danger = true) { reporting = true },
                        ),
                    ).filter { it.isNotEmpty() }
                    Header(item, user, style, imageLoader, menu, onAvatar = { avatarOpen = true })
                }
                user?.description?.takeIf { it.isNotBlank() }?.let { bio ->
                    item {
                        Text(
                            text = bio,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            // The tapped message.
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        MessageRow(item.copy(historical = false), style, imageLoader, onGesture = null)
                    }
                }
            }

            item {
                val copyIcon = ImageVector.vectorResource(R.drawable.ic_content_copy)
                val reply = ButtonAction(R.string.action_reply, Icons.AutoMirrored.Filled.Send) { onReply?.invoke(); onDismiss() }
                    .takeIf { canReply }
                val copy = ButtonAction(R.string.action_copy, copyIcon) {
                    clipboard.setText(AnnotatedString(item.text.ifEmpty { item.systemText.orEmpty() }))
                    onDismiss()
                }
                val mention = ButtonAction(R.string.action_mention, Icons.Default.Person) { onMention?.invoke(); onDismiss() }
                    .takeIf { isUserMessage && !guest && onMention != null }
                // They swap places, Mention stays in the middle.
                val actions = if (copyFirst) listOfNotNull(copy, mention, reply) else listOfNotNull(reply, mention, copy)
                ConnectedButtons(actions, tonal = true, modifier = Modifier.padding(horizontal = 16.dp))
            }

            // Lower and smaller than the actions above: a mod wants them at hand, not in the way.
            if (canModerate && isUserMessage && !item.isOwn) {
                item {
                    val actions = listOfNotNull(
                        ButtonAction(R.string.action_delete, Icons.Default.Delete) { onDelete(); onDismiss() }
                            .takeIf { !item.id.startsWith("local-") && !item.deleted },
                        ButtonAction(
                            R.string.action_timeout,
                            Icons.Default.Warning,
                            choices = TIMEOUTS.map { (label, seconds) -> ButtonChoice(label) { onTimeout(seconds); onDismiss() } },
                            choicesLabel = R.string.timeout_choose,
                        ) { onTimeout(DEFAULT_TIMEOUT); onDismiss() },
                        ButtonAction(R.string.action_ban, Icons.Default.Lock, danger = true) { onBan(); onDismiss() },
                    )
                    ConnectedButtons(
                        actions,
                        tonal = false,
                        compact = true,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    )
                }
            }

            if (isUserMessage) {
                item {
                    HorizontalDivider(Modifier.padding(top = 12.dp))
                    Text(
                        text = stringResource(R.string.user_recent_messages),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                when {
                    messages.isEmpty() -> item {
                        Text(
                            stringResource(R.string.user_no_messages),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    else -> items(messages.asReversed(), key = { "r" + it.id }) { m ->
                        MessageRow(m, style, imageLoader, onGesture = null)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (avatarOpen) user?.profileImageUrl?.takeIf { it.isNotEmpty() }?.let { url ->
        // Twitch avatars are 300 px; larger would only blur.
        Dialog(onDismissRequest = { avatarOpen = false }) {
            AsyncImage(
                model = url,
                contentDescription = stringResource(R.string.user_avatar, item.displayName ?: item.login.orEmpty()),
                imageLoader = imageLoader,
                modifier = Modifier
                    .size(300.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { avatarOpen = false },
            )
        }
    }

    if (reporting) {
        val login = item.login.orEmpty()
        ReportDialog(
            displayName = item.displayName ?: login,
            login = login,
            message = item.text.ifEmpty { item.systemText.orEmpty() },
            alreadyBlocked = blocked,
            onBlock = { onBlockLogin(login) },
            onDismiss = { reporting = false; onDismiss() },
        )
    }
}

@Composable
private fun Header(
    item: ChatItem,
    user: HelixUser?,
    style: ChatStyle,
    imageLoader: ImageLoader,
    menu: List<List<ButtonAction>>,
    onAvatar: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        AsyncImage(
            model = user?.profileImageUrl,
            contentDescription = null,
            imageLoader = imageLoader,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(enabled = !user?.profileImageUrl.isNullOrEmpty(), onClick = onAvatar),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = style.nameOf(item.login, item.displayName ?: item.login.orEmpty()),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = readableNameColor(item.color, item.login, style.dark, style.nameColors),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOfNotNull(
                item.login?.takeIf { !it.equals(item.displayName, ignoreCase = true) }?.let { "@$it" },
                when (user?.broadcasterType) {
                    "partner" -> stringResource(R.string.user_partner)
                    "affiliate" -> stringResource(R.string.user_affiliate)
                    else -> null
                },
            ).joinToString(" \u00B7 ")
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            formatDate(user?.createdAt)?.let {
                Text(
                    stringResource(R.string.user_created, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.badges.isNotEmpty()) BadgeRow(item, imageLoader)
        }
        if (menu.isNotEmpty()) MoreMenu(menu, Modifier.align(Alignment.Top))
    }
}

/** The ⋮ button and its menu, in groups. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MoreMenu(groups: List<List<ButtonAction>>, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
        }
        DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }) {
            groups.forEachIndexed { g, group ->
                if (g > 0) Spacer(Modifier.height(MenuDefaults.GroupSpacing))
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(g, groups.size)) {
                    group.forEachIndexed { i, action ->
                        val color = if (action.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        DropdownMenuItem(
                            text = { Text(stringResource(action.label)) },
                            leadingIcon = action.icon?.let { { Icon(it, contentDescription = null) } },
                            shape = MenuDefaults.itemShape(i, group.size).shape,
                            colors = MenuDefaults.itemColors(textColor = color, leadingIconColor = color),
                            onClick = { open = false; action.onClick() },
                        )
                    }
                }
            }
        }
    }
}

/** Badges in one line below the name, scrolling sideways if needed. */
@Composable
private fun BadgeRow(item: ChatItem, imageLoader: ImageLoader) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
    ) {
        item.badges.forEach { badge ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                AsyncImage(model = badge.url, contentDescription = null, imageLoader = imageLoader, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    badge.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Buttons share the row; labels are cut instead of wrapping. */
/** The lengths offered behind Timeout, in seconds; tapping Timeout itself takes [DEFAULT_TIMEOUT]. */
private val TIMEOUTS = listOf(
    R.string.timeout_1m to 60,
    R.string.timeout_10m to 600,
    R.string.timeout_1h to 3_600,
    R.string.timeout_1d to 86_400,
)

private const val DEFAULT_TIMEOUT = 600

private fun formatDate(iso: String?): String? = iso?.takeIf { it.isNotEmpty() }?.let {
    runCatching {
        Instant.parse(it).atZone(ZoneId.systemDefault()).toLocalDate()
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }.getOrNull()
}
