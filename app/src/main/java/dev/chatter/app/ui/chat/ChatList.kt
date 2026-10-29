package dev.chatter.app.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import dev.chatter.app.R
import dev.chatter.app.ai.CatchUp
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.Segment
import dev.chatter.app.chat.isHistoryLoading
import dev.chatter.app.ui.BetaBadge
import dev.chatter.app.ui.channels.formatCount
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The message list for UI Automator. The benchmark module repeats the name. */
const val CHAT_LIST_TAG = "chat"

/**
 * The message list of a channel or combined chat. Newest at the bottom (reverseLayout); follows new
 * messages unless the user scrolled up.
 *
 * [channels] marks messages with their channel, by login; only combined chats pass it. [partners]
 * marks Shared Chat partner messages, by channel id, on every page.
 *
 * [readMark] is how far the page was read when the user last left it; newer messages get a line and
 * a chip that jumps there (see [ReadMark]). Only the page in front passes [onSeen], which receives
 * the newest message on screen, and [onMentionsSeen], which receives the visible mentions by id.
 * [onCatchUp] offers a summary of a long stretch of new messages and receives them, oldest first.
 *
 * [jumpTo] is the id of a message to scroll to and flash; [onJumped] then says whether it was still
 * there, and the caller clears [jumpTo].
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
    onCatchUp: ((List<ChatItem>) -> Unit)? = null,
    jumpTo: String? = null,
    onJumped: (found: Boolean) -> Unit = {},
) {
    // Deleted messages are already filtered by the repository.
    val items by messages.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var follow by remember { mutableStateOf(true) }
    // Only the user's own drags decide whether to follow new messages; our scroll animations must
    // not turn it off.
    var userScrolling by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) userScrolling = true }
    }
    // The newest message when the user left the bottom; the button counts what came after it.
    var leftAt by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(follow) { leftAt = if (follow) null else items.lastOrNull()?.id }
    val newSinceLeft = remember(items, leftAt) {
        leftAt?.let { id -> items.indexOfLast { it.id == id }.takeIf { it >= 0 }?.let { items.lastIndex - it } } ?: 0
    }
    val unseen = remember(items, readMark) { readMark?.unseenIn(items) }
    // The chip helps find the line; once the line was on screen or the user scrolled to the bottom,
    // it is done.
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
        // reverseLayout: the first visible row is the newest on screen.
        snapshotFlow { items.getOrNull(items.size - 1 - listState.firstVisibleItemIndex) }
            .filterNotNull()
            .distinctUntilChanged { a, b -> a.id == b.id }
            .collect { currentOnSeen?.invoke(ReadMark(it.id, it.timestamp)) }
    }
    val currentOnMentionsSeen by rememberUpdatedState(onMentionsSeen)
    if (onMentionsSeen != null) LaunchedEffect(listState) {
        snapshotFlow {
            // Checked by key: a new message shifts every index before the layout catches up.
            listState.layoutInfo.visibleItemsInfo.mapNotNullTo(HashSet()) { row ->
                items.getOrNull(items.size - 1 - row.index)?.takeIf { it.isMention && it.id == row.key }?.id
            }
        }
            .filter { it.isNotEmpty() }
            .distinctUntilChanged()
            .collect { currentOnMentionsSeen?.invoke(it) }
    }
    // Always a jump to the newest message, never animated. A new message at index 0 pushes the
    // anchor up a row; animating the viewport while the rows animate into place gives two motions
    // of the same distance at different speeds, which jolts. With smooth scrolling on, the rows do
    // the moving (see animateItem below).
    LaunchedEffect(items) {
        if (follow && items.isNotEmpty() && !userScrolling) listState.scrollToItem(0)
    }

    var flashing by remember { mutableStateOf<String?>(null) }
    val flash = remember { Animatable(0f) }
    val currentOnJumped by rememberUpdatedState(onJumped)
    LaunchedEffect(jumpTo) {
        val target = jumpTo ?: return@LaunchedEffect
        // Right after a start the history may still be on its way, and the message with it.
        val found = withTimeoutOrNull(JUMP_WAIT_MS) {
            snapshotFlow { items }.first { list -> list.isNotEmpty() && list.none { it.isHistoryLoading() } }
        }?.takeIf { list -> list.any { it.id == target } }
        if (found == null) {
            listState.scrollToItem(0)
            follow = true
            currentOnJumped(false)
            return@LaunchedEffect
        }
        val index = found.size - 1 - found.indexOfLast { it.id == target }
        follow = false
        // reverseLayout puts the target row at the bottom; half a screen back puts it in the middle.
        listState.scrollToItem(index)
        val row = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 0
        listState.scrollBy(-((listState.layoutInfo.viewportSize.height - row) / 2).coerceAtLeast(0).toFloat())
        // A message among the newest cannot move up; then the list stays at the bottom and follows.
        follow = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        chipDone = true
        currentOnJumped(true)
        // Its own coroutine: clearing jumpTo restarts this effect, which must not cut the flash short.
        scope.launch {
            flashing = target
            flash.snapTo(0f)
            flash.animateTo(1f, tween(FLASH_IN_MS))
            flash.animateTo(0f, tween(FLASH_OUT_MS))
            flashing = null
        }
    }
    val flashColor = style.mentionBackground.copy(alpha = FLASH_ALPHA)

    Box(modifier) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(vertical = 4.dp),
            // UI Automator only sees a Compose test tag when exposed as a resource id; the baseline
            // profile and the macrobenchmark use it to find the list.
            modifier = Modifier
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true }
                .testTag(CHAT_LIST_TAG),
        ) {
            // reverseLayout: index i is the i-th newest message.
            val count = items.size
            items(
                count = count,
                key = { items[count - 1 - it].id },
                contentType = { items[count - 1 - it].kind },
            ) { index ->
                val item = items[count - 1 - index]
                val rowModifier = (if (smoothScrolling) {
                    Modifier.animateItem(fadeInSpec = ROW_FADE_IN, placementSpec = ROW_PLACEMENT, fadeOutSpec = null)
                } else Modifier).then(
                    // Behind the row, whose own background is see-through, so the text stays readable.
                    if (item.id == flashing) Modifier.drawBehind { drawRect(flashColor, alpha = flash.value) } else Modifier,
                )
                // Where it was written; for a partner's message not the channel it arrived in.
                val mark = item.sourceRoomId?.let(partners::get) ?: channels?.get(item.channel)
                if (index == lineIndex) {
                    // Part of the row, not its own row: the keys stay the messages' and the line
                    // stays above its message.
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
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            ) {
                UnseenChip(
                    unseen = unseen,
                    onClick = {
                        scope.launch {
                            follow = false
                            // reverseLayout puts the target row at the bottom; scrolled back a
                            // screen, the line is at the top with the new messages below.
                            listState.scrollToItem(lineIndex)
                            val row = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == lineIndex }?.size ?: 0
                            listState.scrollBy(-(listState.layoutInfo.viewportSize.height - row).coerceAtLeast(0).toFloat())
                            chipDone = true
                        }
                    },
                )
                if (onCatchUp != null && unseen.count >= CatchUp.MIN_MESSAGES) {
                    CatchUpChip { onCatchUp(items.subList(unseen.first, items.size).toList()) }
                }
            }
        }
        NewMessagesButton(
            visible = !follow,
            count = newSinceLeft,
            onClick = {
                scope.launch {
                    listState.scrollToItem(0)
                    follow = true
                    chipDone = true
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp),
        )
    }
}

/**
 * Back to the newest message, shown while the user reads further up. Springs in and out, and counts
 * what arrived since they left the bottom, growing with the number.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NewMessagesButton(visible: Boolean, count: Int, onClick: () -> Unit, modifier: Modifier) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(motion.fastSpatialSpec()) + fadeIn(motion.fastEffectsSpec()),
        exit = scaleOut(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        modifier = modifier,
    ) {
        ExtendedFloatingActionButton(
            onClick = onClick,
            icon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) },
            text = {
                // The new number rolls in from below, like a counter.
                AnimatedContent(
                    targetState = count,
                    transitionSpec = {
                        (slideInVertically(motion.fastSpatialSpec()) { it } + fadeIn(motion.fastEffectsSpec()))
                            .togetherWith(slideOutVertically(motion.fastSpatialSpec()) { -it } + fadeOut(motion.fastEffectsSpec()))
                            .using(SizeTransform(clip = false) { _, _ -> motion.fastSpatialSpec() })
                    },
                    label = "new-messages",
                ) { n ->
                    Text(
                        if (n > 0) pluralStringResource(R.plurals.new_messages_count, n, formatCount(n))
                        else stringResource(R.string.more_messages),
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** How long a jump waits for the history, which may bring its message. */
private const val JUMP_WAIT_MS = 10_000L
private const val FLASH_IN_MS = 150
private const val FLASH_OUT_MS = 1_200
/** The highlight color at this alpha, stronger than a mention's own background. */
private const val FLASH_ALPHA = 0.5f

/** Shared by every row instead of built per row and frame. */
private val ROW_FADE_IN = tween<Float>(120)

/** Quick and without overshoot: a soft spring never settles while a busy chat pushes rows up. */
private val ROW_PLACEMENT = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
    visibilityThreshold = IntOffset.VisibilityThreshold,
)

/** The line above the first message that arrived while the user was away. */
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

/** How much arrived while away; tapping jumps to the start of it. */
@Composable
private fun UnseenChip(unseen: Unseen, onClick: () -> Unit, modifier: Modifier = Modifier) {
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

/** Asks for a summary of what arrived while away; see [CatchUp]. */
@Composable
private fun CatchUpChip(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shadowElevation = 3.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Icon(ImageVector.vectorResource(R.drawable.ic_auto_awesome), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.catch_up_action), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            BetaBadge()
        }
    }
}
