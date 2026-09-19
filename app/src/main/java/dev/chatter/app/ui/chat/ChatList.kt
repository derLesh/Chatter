package dev.chatter.app.ui.chat

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
    onAction: (ChatItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items by messages.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var follow by remember { mutableStateOf(true) }

    // Decide whether to keep following only once a scroll gesture has ended.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) follow = listState.firstVisibleItemIndex == 0
        }
    }
    LaunchedEffect(items) {
        if (follow && items.isNotEmpty() && !listState.isScrollInProgress) listState.scrollToItem(0)
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
                MessageRow(items[count - 1 - index], style, imageLoader, onAction)
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
