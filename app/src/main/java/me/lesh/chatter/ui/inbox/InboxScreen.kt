package me.lesh.chatter.ui.inbox

import android.text.format.DateUtils
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import me.lesh.chatter.ui.theme.isAppInDarkTheme
import me.lesh.chatter.ui.theme.readableNameColor
import me.lesh.chatter.util.INBOX_TAB_MENTIONS
import me.lesh.chatter.util.INBOX_TAB_WHISPERS
import kotlinx.coroutines.launch

/** Mentions and whispers in two tabs. Unlike the chat buffers this keeps them across restarts. */
@Composable
fun InboxScreen(vm: MainViewModel, onOpenMention: (InboxMention) -> Unit, onBack: () -> Unit) {
    val mentions by vm.inboxMentions.collectAsStateWithLifecycle()
    val whispers by vm.inboxWhispers.collectAsStateWithLifecycle()
    val mentionUnread by vm.mentionUnread.collectAsStateWithLifecycle()
    val whisperUnread by vm.whisperUnread.collectAsStateWithLifecycle()
    val info by vm.channelInfo.collectAsStateWithLifecycle()
    val nicknames by vm.nicknames.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var replyTo by remember { mutableStateOf<InboxWhisper?>(null) }
    val onMentions = pager.currentPage == INBOX_TAB_MENTIONS

    // Requested by a whisper notification or the shortcut.
    val requestedTab by vm.requestedInbox.collectAsStateWithLifecycle()
    LaunchedEffect(requestedTab) {
        val tab = requestedTab ?: return@LaunchedEffect
        pager.scrollToPage(tab)
        vm.requestedInbox.value = null
    }

    // While the whisper tab is open, arriving whispers need no notification and the posted ones are
    // cleared.
    DisposableEffect(onMentions) {
        vm.setWhispersVisible(!onMentions)
        onDispose { vm.setWhispersVisible(false) }
    }

    InboxContent(
        pager = pager,
        mentions = mentions,
        whispers = whispers,
        mentionUnread = mentionUnread,
        whisperUnread = whisperUnread,
        info = info,
        nameOf = { login, displayName -> chatterName(displayName, login, settings.nameStyle, nicknames[login.lowercase()]) },
        imageLoader = vm.imageLoader,
        snackbar = snackbar,
        onBack = onBack,
        onOpenMention = { mention ->
            vm.markInboxRead(mention)
            onOpenMention(mention)
        },
        onOpenWhisper = { whisper ->
            vm.markWhisperRead(whisper)
            replyTo = whisper
        },
        onMarkAllRead = { tab -> if (tab == INBOX_TAB_MENTIONS) vm.markInboxRead() else vm.markWhispersRead() },
        onClear = { tab -> if (tab == INBOX_TAB_MENTIONS) vm.clearInbox() else vm.clearWhispers() },
    )

    replyTo?.let { whisper ->
        WhisperReplyDialog(
            name = chatterName(whisper.displayName, whisper.login, settings.nameStyle, nicknames[whisper.login.lowercase()]),
            quoted = whisper.text,
            onSend = { text ->
                // Twitch may refuse a whisper; the snackbar shows its answer.
                scope.launch { snackbar.showSnackbar(vm.sendWhisper(whisper, text)) }
            },
            onDismiss = { replyTo = null },
        )
    }
}

/** The inbox as it looks, without the view model, so it can be shown with sample data. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InboxContent(
    pager: PagerState,
    mentions: List<InboxMention>,
    whispers: List<InboxWhisper>,
    mentionUnread: Int,
    whisperUnread: Int,
    info: Map<String, ChannelInfo>,
    nameOf: (login: String, displayName: String) -> String,
    imageLoader: ImageLoader,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onOpenMention: (InboxMention) -> Unit,
    onOpenWhisper: (InboxWhisper) -> Unit,
    /** Gets the tab in front, [INBOX_TAB_MENTIONS] or [INBOX_TAB_WHISPERS]. */
    onMarkAllRead: (tab: Int) -> Unit,
    onClear: (tab: Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // The bar's actions belong to the tab in front.
    val onMentions = pager.currentPage == INBOX_TAB_MENTIONS
    val hasItems = if (onMentions) mentions.isNotEmpty() else whispers.isNotEmpty()

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
                    if (hasItems) {
                        IconButton(onClick = { onMarkAllRead(pager.currentPage) }) {
                            Icon(Icons.Default.Check, stringResource(R.string.inbox_mark_all_read))
                        }
                        IconButton(onClick = { onClear(pager.currentPage) }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.inbox_clear))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = pager.currentPage) {
                InboxTab(R.string.inbox_tab_mentions, mentionUnread, onMentions) {
                    scope.launch { pager.animateScrollToPage(INBOX_TAB_MENTIONS) }
                }
                InboxTab(R.string.inbox_tab_whispers, whisperUnread, !onMentions) {
                    scope.launch { pager.animateScrollToPage(INBOX_TAB_WHISPERS) }
                }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                when (page) {
                    INBOX_TAB_MENTIONS -> MentionList(mentions, info, nameOf, imageLoader, onOpenMention)
                    else -> WhisperList(whispers, nameOf, onOpenWhisper)
                }
            }
        }
    }
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
    imageLoader: ImageLoader,
    onOpen: (InboxMention) -> Unit,
) {
    if (mentions.isEmpty()) {
        EmptyTab(R.string.inbox_empty, R.string.inbox_empty_hint)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(mentions, key = { it.id }) { mention ->
            InboxRow(
                title = stringResource(
                    R.string.inbox_from,
                    nameOf(mention.login, mention.displayName),
                    info[mention.channel]?.displayName ?: mention.channel,
                ),
                text = mention.text,
                timestamp = mention.timestamp,
                read = mention.read,
                avatar = { ChannelAvatar(info[mention.channel], imageLoader, 40.dp) },
                onClick = { onOpen(mention) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
        }
    }
}

@Composable
private fun WhisperList(
    whispers: List<InboxWhisper>,
    nameOf: (login: String, displayName: String) -> String,
    onOpen: (InboxWhisper) -> Unit,
) {
    if (whispers.isEmpty()) {
        EmptyTab(R.string.inbox_whispers_empty, R.string.inbox_whispers_empty_hint)
        return
    }
    val dark = isAppInDarkTheme()
    LazyColumn(Modifier.fillMaxSize()) {
        items(whispers, key = { it.id }) { whisper ->
            val name = nameOf(whisper.login, whisper.displayName)
            InboxRow(
                title = name,
                text = whisper.text,
                timestamp = whisper.timestamp,
                read = whisper.read,
                avatar = { InitialAvatar(name, readableNameColor(whisper.color, whisper.login, dark)) },
                onClick = { onOpen(whisper) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
        }
    }
}

/** Whispers have no picture; the sender's initial in their color stands in. */
@Composable
private fun InitialAvatar(name: String, color: Color) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = 0.25f)),
    ) {
        Text(
            name.take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
        )
    }
}

@Composable
private fun InboxRow(
    title: String,
    text: String,
    timestamp: Long,
    read: Boolean,
    avatar: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val time = DateUtils.getRelativeTimeSpanString(
        timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
    ).toString()
    ListItem(
        headlineContent = {
            Text(
                title,
                fontWeight = if (read) FontWeight.Normal else FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = { Text(text, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Box(contentAlignment = Alignment.TopEnd) {
                avatar()
                if (!read) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        },
        trailingContent = { Text(time, style = MaterialTheme.typography.labelSmall) },
        colors = ListItemDefaults.colors(
            containerColor = if (read) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

@Composable
private fun EmptyTab(title: Int, hint: Int) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(32.dp),
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(hint),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
