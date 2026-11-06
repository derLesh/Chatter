package me.lesh.chatter.ui.inbox

import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material.icons.filled.Email
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import me.lesh.chatter.chat.WhisperConversation
import me.lesh.chatter.chat.whispersWith
import me.lesh.chatter.ui.FormSheet
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.outlined.Delete
import java.util.Date
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.time.LocalDate
import java.time.Instant
import android.text.format.DateFormat
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.contentColorFor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import me.lesh.chatter.R
import me.lesh.chatter.channels.ChannelInfo
import me.lesh.chatter.chat.InboxMention
import me.lesh.chatter.chat.InboxWhisper
import me.lesh.chatter.chat.chatterName
import me.lesh.chatter.ui.MainViewModel
import me.lesh.chatter.ui.channels.ChannelAvatar
import me.lesh.chatter.ui.rememberPredictiveTransition
import me.lesh.chatter.ui.slideBetweenScreens
import me.lesh.chatter.ui.theme.isAppInDarkTheme
import me.lesh.chatter.ui.theme.isPureBlack
import me.lesh.chatter.ui.theme.readableNameColor
import me.lesh.chatter.util.INBOX_TAB_MENTIONS
import me.lesh.chatter.util.INBOX_TAB_WHISPERS
import kotlinx.coroutines.launch

/** Mentions and whispers in two tabs. Unlike the chat buffers this keeps them across restarts. */
@Composable
fun InboxScreen(vm: MainViewModel, onOpenMention: (InboxMention) -> Unit, onBack: () -> Unit) {
    val mentions by vm.inboxMentions.collectAsStateWithLifecycle()
    val whispers by vm.inboxWhispers.collectAsStateWithLifecycle()
    val conversations by vm.whisperConversations.collectAsStateWithLifecycle()
    val mentionUnread by vm.mentionUnread.collectAsStateWithLifecycle()
    val whisperUnread by vm.whisperUnread.collectAsStateWithLifecycle()
    val info by vm.channelInfo.collectAsStateWithLifecycle()
    val nicknames by vm.nicknames.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val target by vm.whisperTarget.collectAsStateWithLifecycle()
    val nameOf = { login: String, displayName: String -> chatterName(displayName, login, settings.nameStyle, nicknames[login.lowercase()]) }

    val pager = rememberPagerState { 2 }
    val snackbar = remember { SnackbarHostState() }
    var clearing by rememberSaveable { mutableStateOf<Int?>(null) }
    val onMentions = pager.currentPage == INBOX_TAB_MENTIONS

    // Requested by a whisper notification, the shortcut or a user card.
    val requestedTab by vm.requestedInbox.collectAsStateWithLifecycle()
    LaunchedEffect(requestedTab) {
        val tab = requestedTab ?: return@LaunchedEffect
        pager.scrollToPage(tab)
        vm.requestedInbox.value = null
    }

    // While the whispers are on screen, arriving ones need no notification and the posted ones are
    // cleared.
    DisposableEffect(onMentions) {
        vm.setWhispersVisible(!onMentions)
        onDispose { vm.setWhispersVisible(false) }
    }

    val page = target?.let { InboxPage.Chat(it) } ?: InboxPage.Lists
    rememberPredictiveTransition(
        current = page,
        backTo = InboxPage.Lists.takeIf { page is InboxPage.Chat },
        onBack = vm::closeWhisper,
        label = "inbox",
    ).AnimatedContent(transitionSpec = { slideBetweenScreens(forward = targetState is InboxPage.Chat) }) { shown ->
        when (shown) {
            InboxPage.Lists -> InboxContent(
                pager = pager,
                mentions = mentions,
                conversations = conversations,
                mentionUnread = mentionUnread,
                whisperUnread = whisperUnread,
                info = info,
                nameOf = nameOf,
                ownLogin = vm.ownLogin,
                imageLoader = vm.imageLoader,
                snackbar = snackbar,
                onBack = onBack,
                onOpenMention = { mention ->
                    vm.markInboxRead(mention)
                    onOpenMention(mention)
                },
                onOpenConversation = { vm.openWhisper(it.login, it.userId, it.displayName) },
                onMarkAllRead = { tab -> if (tab == INBOX_TAB_MENTIONS) vm.markInboxRead() else vm.markWhispersRead() },
                onClear = { tab -> clearing = tab },
            )
            is InboxPage.Chat -> {
                val with = shown.target
                val messages = whispersWith(whispers, with.login)
                val conversation = conversations.firstOrNull { it.login == with.login }
                // Read as they arrive while the conversation is open.
                LaunchedEffect(with.login, messages.firstOrNull()?.id) { vm.markConversationRead(with.login) }
                WhisperChat(
                    name = nameOf(with.login, conversation?.displayName ?: with.displayName),
                    nameColor = readableNameColor(conversation?.color, with.login, isAppInDarkTheme()),
                    messages = messages,
                    snackbar = snackbar,
                    onBack = vm::closeWhisper,
                    onSend = { text ->
                        val result = vm.sendWhisper(with.login, conversation?.userId ?: with.userId, text)
                        if (result.sent) null else result.message
                    },
                    onDelete = {
                        vm.clearConversation(with.login)
                        vm.closeWhisper()
                    },
                )
            }
        }
    }

    clearing?.let { tab ->
        ClearInboxSheet(
            tab = tab,
            onConfirm = { if (tab == INBOX_TAB_MENTIONS) vm.clearInbox() else vm.clearWhispers() },
            onDismiss = { clearing = null },
        )
    }
}

/** The tabs, or one whisper conversation over them. */
private sealed interface InboxPage {
    data object Lists : InboxPage
    data class Chat(val target: WhisperTarget) : InboxPage
}

/** The inbox as it looks, without the view model, so it can be shown with sample data. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InboxContent(
    pager: PagerState,
    mentions: List<InboxMention>,
    conversations: List<WhisperConversation>,
    mentionUnread: Int,
    whisperUnread: Int,
    info: Map<String, ChannelInfo>,
    nameOf: (login: String, displayName: String) -> String,
    /** Highlighted in the mentions. */
    ownLogin: String,
    imageLoader: ImageLoader,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onOpenMention: (InboxMention) -> Unit,
    onOpenConversation: (WhisperConversation) -> Unit,
    /** Gets the tab in front, [INBOX_TAB_MENTIONS] or [INBOX_TAB_WHISPERS]. */
    onMarkAllRead: (tab: Int) -> Unit,
    /** Asks to clear the tab in front; nothing is gone until [ClearInboxSheet] is confirmed. */
    onClear: (tab: Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // The bar's actions belong to the tab in front.
    val onMentions = pager.currentPage == INBOX_TAB_MENTIONS
    val hasItems = if (onMentions) mentions.isNotEmpty() else conversations.isNotEmpty()
    val hasUnread = (if (onMentions) mentionUnread else whisperUnread) > 0

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inbox_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (hasUnread) {
                        IconButton(onClick = { onMarkAllRead(pager.currentPage) }) {
                            Icon(painterResource(R.drawable.ic_done_all), stringResource(R.string.inbox_mark_all_read))
                        }
                    }
                    if (hasItems) {
                        IconButton(onClick = { onClear(pager.currentPage) }) {
                            Icon(Icons.Outlined.Delete, stringResource(R.string.inbox_clear))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // No line under the tabs, like the channel tabs.
            PrimaryTabRow(selectedTabIndex = pager.currentPage, divider = {}) {
                InboxTab(R.string.inbox_tab_mentions, mentionUnread, onMentions) {
                    scope.launch { pager.animateScrollToPage(INBOX_TAB_MENTIONS) }
                }
                InboxTab(R.string.inbox_tab_whispers, whisperUnread, !onMentions) {
                    scope.launch { pager.animateScrollToPage(INBOX_TAB_WHISPERS) }
                }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                when (page) {
                    INBOX_TAB_MENTIONS -> MentionList(mentions, info, nameOf, ownLogin, imageLoader, onOpenMention)
                    else -> ConversationList(conversations, nameOf, onOpenConversation)
                }
            }
        }
    }
}

/** Clearing a tab cannot be undone, so it asks first. */
@Composable
internal fun ClearInboxSheet(tab: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    FormSheet(
        title = stringResource(if (tab == INBOX_TAB_MENTIONS) R.string.inbox_clear_mentions_title else R.string.inbox_clear_whispers_title),
        text = stringResource(R.string.inbox_clear_text),
        confirmLabel = stringResource(R.string.inbox_clear_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
    )
}

@Composable
private fun InboxTab(label: Int, unread: Int, selected: Boolean, onClick: () -> Unit) {
    Tab(
        selected = selected,
        onClick = onClick,
        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
                if (unread > 0) UnreadCount(unread, selected)
            }
        },
    )
}

/** Beside the tab name, not over it like a badge, which covered the name's last letters. */
@Composable
private fun UnreadCount(count: Int, selected: Boolean) {
    val container = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
    val description = pluralStringResource(R.plurals.inbox_unread, count, count)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = 20.dp)
            .widthIn(min = 20.dp)
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 6.dp)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = contentColorFor(container),
        )
    }
}

@Composable
private fun MentionList(
    mentions: List<InboxMention>,
    info: Map<String, ChannelInfo>,
    nameOf: (login: String, displayName: String) -> String,
    ownLogin: String,
    imageLoader: ImageLoader,
    onOpen: (InboxMention) -> Unit,
) {
    if (mentions.isEmpty()) {
        EmptyTab(
            icon = painterResource(R.drawable.ic_alternate_email),
            title = stringResource(R.string.inbox_empty),
            hint = if (ownLogin.isEmpty()) stringResource(R.string.inbox_empty_hint)
            else stringResource(R.string.inbox_empty_hint_named, ownLogin),
        )
        return
    }
    val ownChannel = stringResource(R.string.inbox_own_channel)
    // The accent marks what is new: in a read mention the name is only bold.
    val newHighlight = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    val readHighlight = SpanStyle(fontWeight = FontWeight.SemiBold)
    DayList(mentions, InboxMention::timestamp, InboxMention::id) { mention ->
        InboxRow(
            name = nameOf(mention.login, mention.displayName),
            place = if (mention.login.equals(mention.channel, ignoreCase = true)) ownChannel
            else stringResource(R.string.inbox_in_channel, info[mention.channel]?.displayName ?: mention.channel),
            text = remember(mention.text, ownLogin, mention.read, newHighlight) {
                highlightName(mention.text, ownLogin, if (mention.read) readHighlight else newHighlight)
            },
            timestamp = mention.timestamp,
            read = mention.read,
            avatar = { ChannelAvatar(info[mention.channel], imageLoader, AVATAR_SIZE, showLive = false) },
            onClick = { onOpen(mention) },
        )
    }
}

/** One row per person, like a messaging app's list of chats. */
@Composable
private fun ConversationList(
    conversations: List<WhisperConversation>,
    nameOf: (login: String, displayName: String) -> String,
    onOpen: (WhisperConversation) -> Unit,
) {
    if (conversations.isEmpty()) {
        EmptyTab(
            icon = rememberVectorPainter(Icons.Filled.Email),
            title = stringResource(R.string.inbox_whispers_empty),
            hint = stringResource(R.string.inbox_whispers_empty_hint),
        )
        return
    }
    val dark = isAppInDarkTheme()
    val you = stringResource(R.string.inbox_you)
    DayList(conversations, { it.latest.timestamp }, WhisperConversation::login) { conversation ->
        val name = nameOf(conversation.login, conversation.displayName)
        val latest = conversation.latest
        InboxRow(
            name = name,
            place = null,
            text = remember(latest.id, you) {
                buildAnnotatedString {
                    if (latest.outgoing) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(you) }
                        append(": ")
                    }
                    append(latest.text)
                }
            },
            timestamp = latest.timestamp,
            read = conversation.unread == 0,
            unreadCount = conversation.unread,
            avatar = { InitialAvatar(name, readableNameColor(conversation.color, conversation.login, dark)) },
            onClick = { onOpen(conversation) },
        )
    }
}

/**
 * [entries], newest first, as tiles under a heading for each day: strong outer corners and slight
 * ones between, like the settings, so a day reads as one block.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun <T> DayList(
    entries: List<T>,
    timestamp: (T) -> Long,
    key: (T) -> String,
    row: @Composable (T) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val days = remember(entries) {
        entries.groupBy { Instant.ofEpochMilli(timestamp(it)).atZone(zone).toLocalDate() }
    }
    val today = LocalDate.now(zone)
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        days.forEach { (day, onDay) ->
            stickyHeader(key = "day-$day", contentType = "day") { DayHeading(day, today) }
            itemsIndexed(onDay, key = { _, entry -> key(entry) }, contentType = { _, _ -> "row" }) { i, entry ->
                val top = if (i == 0) OUTER_CORNER else INNER_CORNER
                val bottom = if (i == onDay.lastIndex) OUTER_CORNER else INNER_CORNER
                Box(
                    Modifier
                        .animateItem()
                        .padding(bottom = if (i == onDay.lastIndex) 0.dp else 2.dp)
                        .clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)),
                ) { row(entry) }
            }
        }
    }
}

/** "Heute", "Gestern" or the weekday and date. */
@Composable
internal fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> stringResource(R.string.inbox_today)
    today.minusDays(1) -> stringResource(R.string.inbox_yesterday)
    else -> {
        val locale = LocalConfiguration.current.locales[0]
        val skeleton = if (day.year == today.year) "EEEEdMMMM" else "EEEEdMMMMyyyy"
        remember(day, locale) {
            day.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale))
        }
    }
}

@Composable
private fun DayHeading(day: LocalDate, today: LocalDate) {
    Text(
        dayLabel(day, today),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 16.dp, top = 20.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/**
 * Whispers have no picture; the sender's initial in their color stands in. The letter leans towards
 * the text color, since a name color is tuned for the screen background, not for its own tint.
 */
@Composable
internal fun InitialAvatar(name: String, color: Color, size: Dp = AVATAR_SIZE) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val base = if (color == Color.Unspecified) onSurface else color
    // Dark name colors sit closer to their own dark tint, so they need more of the text color.
    val towardsText = if (isAppInDarkTheme()) 0.45f else 0.35f
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(base.copy(alpha = 0.18f))
            // The name follows right beside it; the letter would only repeat its first sound.
            .clearAndSetSemantics {},
    ) {
        Text(
            name.take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = lerp(base, onSurface, towardsText),
        )
    }
}

/**
 * One mention or whisper. Unread rows are tinted and bold, with a dot beside the text; read ones step
 * back to grey text, so the new ones stand out without a band or a line between rows. The channel
 * gets a line of its own, so a long name can never push it out.
 */
@Composable
private fun InboxRow(
    name: String,
    /** Where it was said, under the name; null for whispers. */
    place: String?,
    text: AnnotatedString,
    timestamp: Long,
    read: Boolean,
    avatar: @Composable () -> Unit,
    onClick: () -> Unit,
    /** Shown instead of the dot where it says more, as for a conversation. */
    unreadCount: Int = 0,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val time = remember(timestamp) { DateFormat.getTimeFormat(context).format(Date(timestamp)) }
    val unreadTint = when {
        colors.isPureBlack -> UNREAD_TINT_BLACK
        isAppInDarkTheme() -> UNREAD_TINT_DARK
        else -> UNREAD_TINT_LIGHT
    }
    val unreadState = stringResource(R.string.inbox_unread_state)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (read) colors.surfaceContainer else lerp(colors.surfaceContainer, colors.primaryContainer, unreadTint))
            .clickable(onClick = onClick)
            .semantics { if (!read) stateDescription = unreadState }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        avatar()
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (read) FontWeight.Medium else FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    time,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            if (place != null) {
                Text(
                    place,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row(Modifier.padding(top = 4.dp)) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (read) colors.onSurfaceVariant else colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                when {
                    unreadCount > 0 -> Box(Modifier.padding(start = 12.dp, top = 1.dp)) { UnreadCount(unreadCount, selected = true) }
                    !read -> Box(
                        Modifier
                            .padding(start = 12.dp, top = 6.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(colors.primary),
                    )
                }
            }
        }
    }
}

/** [text] with every mention of [login], with or without the @, in [style]. */
internal fun highlightName(text: String, login: String, style: SpanStyle): AnnotatedString {
    if (login.isEmpty()) return AnnotatedString(text)
    val name = Regex("(?<![\\w@])@?${Regex.escape(login)}(?!\\w)", RegexOption.IGNORE_CASE)
    return buildAnnotatedString {
        append(text)
        name.findAll(text).forEach { addStyle(style, it.range.first, it.range.last + 1) }
    }
}

private val AVATAR_SIZE = 44.dp
private val OUTER_CORNER = 24.dp
private val INNER_CORNER = 4.dp

/**
 * How far an unread tile leans towards the accent: enough to find it, not a coloured band. Dark
 * themes need less, since their accent container is far more saturated than the tiles around it.
 */
private const val UNREAD_TINT_LIGHT = 0.28f
private const val UNREAD_TINT_DARK = 0.13f

/** Pure black tiles are a step darker, so the same tint would hardly show on them. */
private const val UNREAD_TINT_BLACK = 0.24f

/** A picture of what would be here, and one line on how it gets here. */
@Composable
internal fun EmptyTab(icon: Painter, title: String, hint: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        // A little above the middle, where the eye lands first.
        modifier = Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, bottom = 64.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(40.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp).semantics { heading() },
        )
        Text(
            hint,
            // Balanced lines, rather than a full first line and a stub.
            style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Paragraph),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp).padding(top = 8.dp),
        )
    }
}
