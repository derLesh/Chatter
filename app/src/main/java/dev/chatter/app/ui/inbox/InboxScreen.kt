package dev.chatter.app.ui.inbox

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.chatter.app.R
import dev.chatter.app.channels.ChannelInfo
import dev.chatter.app.chat.InboxMention
import dev.chatter.app.chat.InboxWhisper
import dev.chatter.app.ui.MainViewModel
import dev.chatter.app.ui.channels.ChannelAvatar
import dev.chatter.app.ui.theme.isAppInDarkTheme
import dev.chatter.app.ui.theme.readableNameColor
import dev.chatter.app.util.INBOX_TAB_MENTIONS
import dev.chatter.app.util.INBOX_TAB_WHISPERS
import kotlinx.coroutines.launch

/**
 * Everything written to the user personally, in two tabs: mentions out of the channels, and
 * whispers, which belong to no channel at all. The chat buffers forget, this does not — it is
 * where they find out who wanted something from them while the app was closed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(vm: MainViewModel, onOpenChannel: (String) -> Unit, onBack: () -> Unit) {
    // Without this, back would leave the app instead of going back to the chat behind it.
    BackHandler(onBack = onBack)

    val mentions by vm.inboxMentions.collectAsStateWithLifecycle()
    val whispers by vm.inboxWhispers.collectAsStateWithLifecycle()
    val mentionUnread by vm.mentionUnread.collectAsStateWithLifecycle()
    val whisperUnread by vm.whisperUnread.collectAsStateWithLifecycle()
    val info by vm.channelInfo.collectAsStateWithLifecycle()
    val nicknames by vm.nicknames.collectAsStateWithLifecycle()

    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var replyTo by remember { mutableStateOf<InboxWhisper?>(null) }
    // The actions in the bar belong to whichever tab is in front, not to the inbox as a whole.
    val onMentions = pager.currentPage == INBOX_TAB_MENTIONS
    val hasItems = if (onMentions) mentions.isNotEmpty() else whispers.isNotEmpty()

    // A whisper notification asks for its tab; the shortcut for the other one.
    val requestedTab by vm.requestedInbox.collectAsStateWithLifecycle()
    LaunchedEffect(requestedTab) {
        val tab = requestedTab ?: return@LaunchedEffect
        pager.scrollToPage(tab)
        vm.requestedInbox.value = null
    }

    // While the whisper tab is the thing being read, a whisper arriving in it needs no
    // notification, and the ones already posted have been answered by opening this.
    DisposableEffect(onMentions) {
        vm.setWhispersVisible(!onMentions)
        onDispose { vm.setWhispersVisible(false) }
    }

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
                        IconButton(onClick = { if (onMentions) vm.markInboxRead() else vm.markWhispersRead() }) {
                            Icon(Icons.Default.Check, stringResource(R.string.inbox_mark_all_read))
                        }
                        IconButton(onClick = { if (onMentions) vm.clearInbox() else vm.clearWhispers() }) {
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
                    INBOX_TAB_MENTIONS -> MentionList(
                        mentions = mentions,
                        info = info,
                        nicknames = nicknames,
                        imageLoader = vm.imageLoader,
                        onOpen = { mention ->
                            vm.markInboxRead(mention)
                            onOpenChannel(mention.channel)
                        },
                    )
                    else -> WhisperList(
                        whispers = whispers,
                        nicknames = nicknames,
                        onOpen = { whisper ->
                            vm.markWhisperRead(whisper)
                            replyTo = whisper
                        },
                    )
                }
            }
        }
    }

    replyTo?.let { whisper ->
        WhisperReplyDialog(
            name = nicknames[whisper.login.lowercase()] ?: whisper.displayName,
            quoted = whisper.text,
            onSend = { text ->
                // Twitch may refuse a whisper for reasons only it knows, so the answer is not
                // over until it says so - which is what lands in the snackbar.
                scope.launch { snackbar.showSnackbar(vm.sendWhisper(whisper, text)) }
            },
            onDismiss = { replyTo = null },
        )
    }
}

@Composable
private fun InboxTab(label: Int, unread: Int, selected: Boolean, onClick: () -> Unit) {
    Tab(
        selected = selected,
        onClick = onClick,
        text = {
            BadgedBox(badge = { if (unread > 0) Badge { Text(unread.coerceAtMost(99).toString()) } }) {
                Text(stringResource(label))
            }
        },
    )
}

@Composable
private fun MentionList(
    mentions: List<InboxMention>,
    info: Map<String, ChannelInfo>,
    nicknames: Map<String, String>,
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
                    nicknames[mention.login.lowercase()] ?: mention.displayName,
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
    nicknames: Map<String, String>,
    onOpen: (InboxWhisper) -> Unit,
) {
    if (whispers.isEmpty()) {
        EmptyTab(R.string.inbox_whispers_empty, R.string.inbox_whispers_empty_hint)
        return
    }
    val dark = isAppInDarkTheme()
    LazyColumn(Modifier.fillMaxSize()) {
        items(whispers, key = { it.id }) { whisper ->
            val name = nicknames[whisper.login.lowercase()] ?: whisper.displayName
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

/** A whisper has no picture to show, so the sender's initial in their own color stands in. */
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
