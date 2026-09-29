package dev.chatter.app.ui.chat

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import dev.chatter.app.R
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.Segment
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** The message list as UI Automator finds it. The benchmark module spells the same name out. */
const val CHAT_LIST_TAG = "chat"

/**
 * The message list of one channel, or of a combined chat. Newest message at the bottom
 * (reverseLayout), follows new messages automatically unless the user scrolled up to read.
 *
 * [channels] marks every message with the channel it came from, by login; only a combined chat
 * passes it, where that is not obvious from the page. [partners] marks the messages a Shared Chat
 * partner sent over, by channel id, on every page: there it is never obvious.
 *
 * [readMark] is how far the page had been read when the user last left it. What arrived since
 * gets a line above it and a chip that jumps there; see [ReadMark]. [onSeen] hears which message
 * is the newest on screen, and only the page in front passes it: the others are not being read.
 * [onMentionsSeen] hears the mentions on screen, by id, under the same rule.
 */
@Composable
fun ChatList(
    messages: StateFlow<List<ChatItem>>,
    style: ChatStyle,
    imageLoader: ImageLoader,
    onGesture: (ChatItem, MessageGesture) -> Unit,
    modifier: Modifier = Modifier,
    smoothScrolling: Boolean = false,
    onEmoteClick: ((Segment.EmoteSeg) -> Unit)? = null,
    channels: Map<String, ChannelMark>? = null,
    partners: Map<String, ChannelMark> = emptyMap(),
    readMark: ReadMark? = null,
    onSeen: ((ReadMark) -> Unit)? = null,
    onMentionsSeen: ((Set<String>) -> Unit)? = null,
) {
    // Already filtered for deleted messages by the repository, which had to copy the buffer anyway.
    val items by messages.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var follow by remember { mutableStateOf(true) }
    // Only the user's own drags decide whether we keep following new messages. Our own
    // (possibly interrupted) scroll animations must not switch following off.
    var userScrolling by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) userScrolling = true }
    }
    val unseen = remember(items, readMark) { readMark?.unseenIn(items) }
    // The chip is for finding the line. Once the line has been on screen, or the user has
    // scrolled back down to the newest message, it has done its job.
    var chipDone by remember(readMark) { mutableStateOf(false) }
    val lineIndex = unseen?.let { items.size - 1 - it.first }
    val lineShown by remember(listState, lineIndex) {
        derivedStateOf { lineIndex != null && listState.layoutInfo.visibleItemsInfo.any { it.index == lineIndex } }
    }
    LaunchedEffect(lineShown) { if (lineShown) chipDone = true }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && userScrolling) {
                userScrolling = false
                follow = listState.firstVisibleItemIndex == 0
                if (follow) chipDone = true
            }
        }
    }
    val currentOnSeen by rememberUpdatedState(onSeen)
    if (onSeen != null) LaunchedEffect(listState) {
        // reverseLayout: the first visible row is the newest one on screen.
        snapshotFlow { items.getOrNull(items.size - 1 - listState.firstVisibleItemIndex) }
            .filterNotNull()
            .distinctUntilChanged { a, b -> a.id == b.id }
            .collect { currentOnSeen?.invoke(ReadMark(it.id, it.timestamp)) }
    }
    val currentOnMentionsSeen by rememberUpdatedState(onMentionsSeen)
    if (onMentionsSeen != null) LaunchedEffect(listState) {
        snapshotFlow {
            // Checked against the row's key: a message that just arrived shifts every index by
            // one before the layout has caught up, and a mention a row below the screen is not seen.
            listState.layoutInfo.visibleItemsInfo.mapNotNullTo(HashSet()) { row ->
                items.getOrNull(items.size - 1 - row.index)?.takeIf { it.isMention && it.id == row.key }?.id
            }
        }
            .filter { it.isNotEmpty() }
            .distinctUntilChanged()
            .collect { currentOnMentionsSeen?.invoke(it) }
    }
    // Always a straight jump to the newest message, never an animated one. A new message is
    // inserted at index 0, which pushes the list's anchor up by a row, and the viewport has to
    // come back down. Animating that while the rows themselves are animating into their new
    // places means two motions of the same distance at different speeds - which is the jolt.
    // With smooth scrolling on, the rows do the visible moving (see animateItem below).
    LaunchedEffect(items) {
        if (follow && items.isNotEmpty() && !userScrolling) listState.scrollToItem(0)
    }

    Box(modifier) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(vertical = 4.dp),
            // How the baseline profile and the macrobenchmark find the list to scroll: UI Automator
            // only sees a Compose test tag where it is handed over as a resource id.
            modifier = Modifier
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true }
                .testTag(CHAT_LIST_TAG),
        ) {
            // reverseLayout puts index 0 at the bottom, so index i shows the i-th newest message.
            val count = items.size
            items(
                count = count,
                key = { items[count - 1 - it].id },
                contentType = { items[count - 1 - it].kind },
            ) { index ->
                val rowModifier = if (smoothScrolling) {
                    // Quick and without overshoot on purpose. A soft spring never settles while
                    // a busy chat keeps pushing rows up, and a list permanently in motion is
                    // exactly what makes it hard to read along.
                    Modifier.animateItem(
                        fadeInSpec = tween(120),
                        placementSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                        fadeOutSpec = null,
                    )
                } else Modifier
                val item = items[count - 1 - index]
                // Where it was written, which for a partner's message is not the channel it came in.
                val mark = item.sourceRoomId?.let(partners::get) ?: channels?.get(item.channel)
                if (index == lineIndex) {
                    // Part of the row rather than a row of its own: the keys stay those of the
                    // messages, and the line cannot end up anywhere but above its message.
                    Column(rowModifier) {
                        UnseenLine()
                        MessageRow(item, style, imageLoader, onGesture, onEmoteClick, mark)
                    }
                } else {
                    Box(rowModifier) { MessageRow(item, style, imageLoader, onGesture, onEmoteClick, mark) }
                }
            }
        }
        if (unseen != null && lineIndex != null && !chipDone && !lineShown) {
            UnseenChip(
                unseen = unseen,
                onClick = {
                    scope.launch {
                        follow = false
                        // reverseLayout puts the row scrolled to at the bottom; pulled back up a
                        // screen, the line is at the top with everything new under it.
                        listState.scrollToItem(lineIndex)
                        val row = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == lineIndex }?.size ?: 0
                        listState.scrollBy(-(listState.layoutInfo.viewportSize.height - row).coerceAtLeast(0).toFloat())
                        chipDone = true
                    }
                },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            )
        }
        if (!follow) {
            ExtendedFloatingActionButton(
                onClick = {
                    scope.launch {
                        listState.scrollToItem(0)
                        follow = true
                        chipDone = true
                    }
                },
                icon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) },
                text = { Text(stringResource(R.string.more_messages)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            )
        }
    }
}

/** The thin line above the first message that arrived while the user was away. */
@Composable
private fun UnseenLine() {
    val color = MaterialTheme.colorScheme.error
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f), color = color)
        Text(
            stringResource(R.string.unseen_line),
            color = color,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/** How much arrived while the user was away; tapping it goes to where that starts. */
@Composable
private fun UnseenChip(unseen: Unseen, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 3.dp,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (unseen.cutOff) pluralStringResource(R.plurals.unseen_messages_more, unseen.count, unseen.count)
                else pluralStringResource(R.plurals.unseen_messages, unseen.count, unseen.count),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
