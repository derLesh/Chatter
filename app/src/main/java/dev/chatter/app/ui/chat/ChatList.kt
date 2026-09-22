package dev.chatter.app.ui.chat

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import dev.chatter.app.R
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.Segment
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The message list of one channel. Newest message at the bottom (reverseLayout), follows new
 * messages automatically unless the user scrolled up to read.
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
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && userScrolling) {
                userScrolling = false
                follow = listState.firstVisibleItemIndex == 0
            }
        }
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
            modifier = Modifier.fillMaxSize(),
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
                Box(rowModifier) { MessageRow(items[count - 1 - index], style, imageLoader, onGesture, onEmoteClick) }
            }
        }
        if (!follow) {
            ExtendedFloatingActionButton(
                onClick = {
                    scope.launch {
                        listState.scrollToItem(0)
                        follow = true
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
