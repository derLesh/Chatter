package me.lesh.chatter.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.lesh.chatter.R
import me.lesh.chatter.badges.Badge
import me.lesh.chatter.chat.ChatItem
import me.lesh.chatter.net.HelixUser
import me.lesh.chatter.ui.theme.readableNameColor
import me.lesh.chatter.util.AvatarColors
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

private val BANNER_HEIGHT = 80.dp
private val AVATAR_SIZE = 64.dp
private val AVATAR_RING = 4.dp

/** How far the profile picture reaches up into the banner. */
private val AVATAR_OVERLAP = 20.dp

/**
 * The least the tabs below the header take. They grow to fit the tapped message, never shrink while
 * the card is open, and are the same for every tab, so switching does not make the sheet jump.
 */
private val MIN_TAB_PAGE_HEIGHT = 240.dp

/** How long the card waits for the profile before opening without it. */
private const val PROFILE_WAIT_MS = 800L

/** Younger accounts get their age marked, since spam and ban evasion mostly come from them. */
private val NEW_ACCOUNT = Duration.ofDays(7)

/**
 * Opened from a message: the author (banner, avatar, badges, account age, bio), then tabs: the
 * tapped message with what can be done with it, the author's recent messages in this channel, and
 * for a mod what can be done to the author. [onWhisper] receives the Twitch id once loaded, saving
 * a lookup. A [guest] gets no actions that write; without [onReply] and [onMention] those two are
 * left out. [note] is the user's private note on the author, empty if there is none. Tapping a
 * badge offers to hide its kind, or to show it again; [onBadgeHidden] receives the choice. A
 * message tapped in the history goes to [onOpenMessage], which opens the card on it.
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
    note: String,
    /** Copy first and Reply last in the row, instead of the reverse. */
    copyFirst: Boolean,
    /** Actions above the tapped message, so they stay in place however long it is. */
    buttonsFirst: Boolean,
    onBlock: (HelixUser, Boolean) -> Unit,
    onBlockLogin: (String) -> Unit,
    onNickname: () -> Unit,
    onNote: () -> Unit,
    onBadgeHidden: (Badge, Boolean) -> Unit,
    onOpenMessage: (ChatItem) -> Unit,
    onReply: (() -> Unit)?,
    onMention: (() -> Unit)?,
    onWhisper: (userId: String?) -> Unit,
    onDelete: () -> Unit,
    /** Seconds, or null until the stream ends; see [PIN_LENGTHS]. */
    onPin: (Int?) -> Unit,
    /** Seconds; see [TIMEOUTS]. */
    onTimeout: (Int) -> Unit,
    onBan: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Keyed by the author, not the message: opening another of their messages from the history
    // keeps what is loaded.
    val author = item.login ?: item.id
    var recent by remember(author) { mutableStateOf<List<ChatItem>?>(null) }
    var user by remember(author) { mutableStateOf<HelixUser?>(null) }
    var reporting by remember(item.id) { mutableStateOf(false) }
    var avatarOpen by remember(item.id) { mutableStateOf(false) }
    var profileWaited by remember(author) { mutableStateOf(false) }
    // Height of the message tab's content in px, the largest of the messages opened on this card.
    var messageHeight by remember(author) { mutableIntStateOf(0) }
    LaunchedEffect(author) { recent = recentMessages() }
    LaunchedEffect(author) {
        // Bio and partner status make the header taller; opened without them, the sheet would
        // jump once they arrive. A slow profile still comes in later.
        val loading = async { profile() }
        user = withTimeoutOrNull(PROFILE_WAIT_MS) { loading.await() }
        profileWaited = true
        if (user == null) user = loading.await()
    }

    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val isUserMessage = item.login != null
    val canReply = !guest && onReply != null && item.canReply && !item.id.startsWith("local-")
    // Only a message Twitch confirmed has the id to pin or delete it by.
    val confirmed = !item.id.startsWith("local-") && !item.deleted
    val pinIcon = ImageVector.vectorResource(R.drawable.ic_push_pin)
    val copyIcon = ImageVector.vectorResource(R.drawable.ic_content_copy)

    if (isUserMessage && !profileWaited) return

    @Composable
    fun MessagePage(modifier: Modifier) {
        Column(modifier) {
            val tapped = @Composable {
                MessageRow(item.copy(historical = false), style.over(MaterialTheme.colorScheme.surfaceContainerLow), imageLoader, onGesture = null)
            }
            if (!buttonsFirst) tapped()
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
            ConnectedButtons(actions, tonal = true, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            // A row of its own: four in one row leave too little room for the labels.
            if (canModerate && isUserMessage && confirmed) {
                ConnectedButtons(
                    listOf(
                        ButtonAction(
                            R.string.action_pin,
                            pinIcon,
                            choices = PIN_LENGTHS.map { (label, seconds) -> ButtonChoice(label) { onPin(seconds); onDismiss() } },
                        ),
                    ),
                    tonal = false,
                    compact = true,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
            if (buttonsFirst) tapped()
        }
    }

    val canModerateAuthor = canModerate && isUserMessage && !item.isOwn

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = isUserMessage),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        // The banner reaches up to the sheet's edge; the handle is drawn on it.
        dragHandle = if (isUserMessage) null else ({ SheetHandle(MaterialTheme.colorScheme.onSurfaceVariant, Modifier.padding(vertical = 12.dp)) }),
    ) {
        if (!isUserMessage) {
            MessagePage(Modifier.fillMaxWidth().padding(bottom = 24.dp))
            return@ModalBottomSheet
        }
        Column(Modifier.fillMaxWidth()) {
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
            Header(item, user, style, imageLoader, menu, onAvatar = { avatarOpen = true }, onBadgeHidden = onBadgeHidden)
            user?.description?.takeIf { it.isNotBlank() }?.let { bio ->
                Text(
                    text = bio,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
                )
            }
            if (!item.isOwn) NoteRow(note, onNote)

            val tabs = if (canModerateAuthor) CardTab.entries else CardTab.entries - CardTab.Moderate
            val pager = rememberPagerState { tabs.size }
            val scope = rememberCoroutineScope()
            // A message opened from the history is shown on its own tab.
            LaunchedEffect(item.id) { if (pager.currentPage != 0) pager.animateScrollToPage(0) }
            Tabs(tabs, pager.currentPage, recent?.size, onPick = { scope.launch { pager.animateScrollToPage(it) } })
            val pageHeight = maxOf(MIN_TAB_PAGE_HEIGHT, with(LocalDensity.current) { messageHeight.toDp() })
            HorizontalPager(pager, Modifier.fillMaxWidth().height(pageHeight), verticalAlignment = Alignment.Top) { page ->
                when (tabs[page]) {
                    CardTab.Message -> MessagePage(
                        Modifier
                            .fillMaxWidth()
                            .wrapContentHeight(Alignment.Top, unbounded = true)
                            .onSizeChanged { messageHeight = maxOf(messageHeight, it.height) }
                            .padding(top = 8.dp, bottom = 16.dp),
                    )
                    CardTab.Moderate -> ModeratePage(
                        canDelete = confirmed,
                        onDelete = { onDelete(); onDismiss() },
                        onTimeout = { onTimeout(it); onDismiss() },
                        onBan = { onBan(); onDismiss() },
                    )
                    CardTab.History -> {
                        val messages = recent.orEmpty()
                        if (recent != null && messages.isEmpty()) {
                            Text(
                                stringResource(R.string.user_no_messages),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
                            )
                        } else {
                            LazyColumn(Modifier.fillMaxSize()) {
                                item { Spacer(Modifier.height(6.dp)) }
                                items(messages.asReversed(), key = { "r" + it.id }) { m ->
                                    val current = m.id == item.id
                                    val background = if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .background(background)
                                            .clickable(enabled = !current) { onOpenMessage(m) },
                                    ) {
                                        MessageRow(m, style.over(background), imageLoader, onGesture = null)
                                    }
                                }
                                item { Spacer(Modifier.height(24.dp)) }
                            }
                        }
                    }
                }
            }
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

/**
 * What a mod can do to the author: the timeout lengths below a heading of their own, one tap
 * each, then deleting the message and banning, the ban in the error colours. The heading sits close
 * to the lengths and apart from the rest, so they read as the timeout's.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModeratePage(canDelete: Boolean, onDelete: () -> Unit, onTimeout: (Int) -> Unit, onBan: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
            Icon(
                ImageVector.vectorResource(R.drawable.ic_timer),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.action_timeout), style = MaterialTheme.typography.titleMedium)
        }
        ConnectedButtons(
            TIMEOUTS.map { (label, seconds) -> ButtonAction(label) { onTimeout(seconds) } },
            tonal = true,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val size = Modifier.weight(1f).heightIn(min = 56.dp)
            if (canDelete) {
                FilledTonalButton(onClick = onDelete, shapes = ButtonDefaults.shapes(), modifier = size) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.user_card_delete_message), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Button(
                onClick = onBan,
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = size,
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_ban), maxLines = 1)
            }
        }
    }
}

private enum class CardTab(val label: Int, val icon: Int) {
    Message(R.string.user_card_tab_message, R.drawable.ic_chat),
    History(R.string.user_card_tab_history, R.drawable.ic_history),
    Moderate(R.string.user_card_tab_moderate, R.drawable.ic_shield),
}

@Composable
private fun SheetHandle(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 32.dp, height = 4.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.6f)),
    )
}

/**
 * The card's tabs, the way X draws a profile's: the chosen one grows and shows its name, the
 * others keep only their icon, and the line below slides along. History carries the number of
 * recent messages once they are loaded.
 */
@Composable
private fun Tabs(tabs: List<CardTab>, selected: Int, historyCount: Int?, onPick: (Int) -> Unit) {
    // Left edge and width of each tab in px, as last laid out; the line follows the chosen one.
    val bounds = remember(tabs) { mutableStateMapOf<Int, Pair<Float, Float>>() }
    Box(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp)) {
            tabs.forEachIndexed { i, tab ->
                val chosen = i == selected
                val weight by animateFloatAsState(if (chosen) 2.2f else 1f, label = "tab weight")
                val color = if (chosen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(weight)
                        .fillMaxHeight()
                        .onPlaced { bounds[i] = it.positionInParent().x to it.size.width.toFloat() }
                        .selectable(selected = chosen, role = Role.Tab, onClick = { onPick(i) }),
                ) {
                    Icon(
                        ImageVector.vectorResource(tab.icon),
                        contentDescription = if (chosen) null else stringResource(tab.label),
                        tint = color,
                        modifier = Modifier.size(20.dp),
                    )
                    AnimatedVisibility(
                        visible = chosen,
                        enter = fadeIn() + expandHorizontally(),
                        exit = fadeOut() + shrinkHorizontally(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(tab.label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
                            if (tab == CardTab.History && historyCount != null) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    historyCount.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(Modifier.align(Alignment.BottomStart), color = MaterialTheme.colorScheme.surfaceContainerHighest)
        bounds[selected]?.let { (left, width) ->
            val x by animateFloatAsState(left, label = "tab line x")
            val w by animateFloatAsState(width, label = "tab line width")
            val density = LocalDensity.current
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset { IntOffset(x.roundToInt(), 0) }
                    .width(with(density) { w.toDp() })
                    .height(3.dp)
                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * The banner with the ⋮ menu on it, and the profile picture reaching into it with name, login,
 * account age and partner status beside it.
 */
@Composable
private fun Header(
    item: ChatItem,
    user: HelixUser?,
    style: ChatStyle,
    imageLoader: ImageLoader,
    menu: List<List<ButtonAction>>,
    onAvatar: () -> Unit,
    onBadgeHidden: (Badge, Boolean) -> Unit,
) {
    val nameColor = readableNameColor(item.color, item.login, style.dark, style.nameColors)
    Box(Modifier.fillMaxWidth()) {
        Banner(user?.profileImageUrl, nameColor, imageLoader)
        SheetHandle(Color.White, Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
        if (menu.isNotEmpty()) MoreMenu(menu, Modifier.align(Alignment.TopEnd).padding(4.dp))
        // Centred against the picture, but kept below the banner, where the text is readable.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = BANNER_HEIGHT - AVATAR_OVERLAP),
        ) {
            AsyncImage(
                model = user?.profileImageUrl,
                contentDescription = null,
                imageLoader = imageLoader,
                modifier = Modifier
                    .size(AVATAR_SIZE + AVATAR_RING * 2)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(AVATAR_RING)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable(enabled = !user?.profileImageUrl.isNullOrEmpty(), onClick = onAvatar),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(top = AVATAR_OVERLAP)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = style.nameOf(item.login, item.displayName ?: item.login.orEmpty()),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = nameColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    item.badges.forEach { badge -> BadgeIcon(badge, badge.kind in style.hiddenBadges, imageLoader, onBadgeHidden) }
                }
                LoginAndAge(item.login, user, style.dark)
                when (user?.broadcasterType) {
                    "partner" -> R.string.user_partner
                    "affiliate" -> R.string.user_affiliate
                    else -> null
                }?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** "@login · Account since …", the age marked while the account is new. */
@Composable
private fun LoginAndAge(login: String?, user: HelixUser?, dark: Boolean) {
    val created = user?.createdAt?.takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val since = created?.let { stringResource(R.string.user_account_since, formatDate(it)) }
    if (login == null && since == null) return
    val isNew = created != null && Duration.between(created, Instant.now()) < NEW_ACCOUNT
    val newColor = if (dark) Color(0xFFFFC94D) else Color(0xFF8A6100)
    val text = buildAnnotatedString {
        if (login != null) append("@$login")
        if (since != null) {
            if (login != null) append(" · ")
            if (isNew) withStyle(SpanStyle(color = newColor, fontWeight = FontWeight.SemiBold)) { append(since) } else append(since)
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Behind the header: a gradient of the profile picture's two main colours. Twitch's default
 * pictures, and the time until the picture is measured, show a gradient of the name colour.
 */
@Composable
private fun Banner(avatarUrl: String?, nameColor: Color, imageLoader: ImageLoader) {
    val hasOwnPicture = !avatarUrl.isNullOrEmpty() && "user-default-pictures" !in avatarUrl
    val colors = if (hasOwnPicture) avatarColors(avatarUrl, imageLoader) else null
    Box(
        Modifier
            .fillMaxWidth()
            .height(BANNER_HEIGHT)
            .background(Brush.linearGradient(colors?.toList() ?: listOf(lerp(nameColor, Color.Black, 0.45f), nameColor))),
    )
}

/** Null until the picture is loaded and measured. */
@Composable
private fun avatarColors(url: String?, imageLoader: ImageLoader): Pair<Color, Color>? {
    val context = LocalContext.current
    val colors by produceState<Pair<Color, Color>?>(null, url) {
        // A small copy is enough to tell the colours apart, and quick to read.
        val request = ImageRequest.Builder(context).data(url).size(48).allowHardware(false).build()
        val bitmap = (imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            AvatarColors.of(pixels)?.let { (a, b) -> Color(a) to Color(b) }
        }
    }
    return colors
}

/** One badge beside the name, as its picture only. Tapping names it and offers to hide its kind. */
@Composable
private fun BadgeIcon(badge: Badge, hidden: Boolean, imageLoader: ImageLoader, onHidden: (Badge, Boolean) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        AsyncImage(
            model = badgePicture(badge.url, badge.urlOnLight),
            contentDescription = badge.title,
            imageLoader = imageLoader,
            modifier = Modifier
                .padding(start = 6.dp)
                .size(20.dp)
                .alpha(if (hidden) 0.4f else 1f)
                .clip(RoundedCornerShape(4.dp))
                .clickable { menuOpen = true },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            Text(
                badge.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (hidden) R.string.badge_show else R.string.badge_hide)) },
                onClick = { menuOpen = false; onHidden(badge, !hidden) },
            )
        }
    }
}

/** The private note below the name, or a quiet way to add one. Tapping either opens the editor. */
@Composable
private fun NoteRow(note: String, onClick: () -> Unit) {
    val icon = ImageVector.vectorResource(R.drawable.ic_edit_note)
    if (note.isEmpty()) {
        TextButton(onClick = onClick, modifier = Modifier.padding(horizontal = 8.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.note_add))
        }
        return
    }
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 16.dp, end = 16.dp, top = 8.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Icon(icon, contentDescription = stringResource(R.string.note_title), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(note, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The ⋮ button on the banner and its menu, in groups. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MoreMenu(groups: List<List<ButtonAction>>, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(
            onClick = { open = true },
            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.3f), contentColor = Color.White),
        ) {
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

/** The lengths Timeout offers, in seconds, with labels short enough for four in a row. */
private val TIMEOUTS = listOf(
    R.string.timeout_short_1m to 60,
    R.string.timeout_short_10m to 600,
    R.string.timeout_short_1h to 3_600,
    R.string.timeout_short_1d to 86_400,
)

/** The lengths Pin offers, in seconds; Twitch takes 30 to 1800, or none for the whole stream. */
private val PIN_LENGTHS = listOf(
    R.string.timeout_1m to 60,
    R.string.pin_5m to 300,
    R.string.pin_30m to 1_800,
    R.string.pin_until_stream_ends to null,
)

private fun formatDate(instant: Instant): String =
    instant.atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
