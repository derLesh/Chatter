package dev.chatter.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import dev.chatter.app.R
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.ReplyInfo
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter

/**
 * One conversation out of a busy chat: the message it started with and every answer to it that is
 * still in the channel, oldest at the top. It is read from the channel's own list, so an answer
 * arriving while the sheet is open turns up at the bottom of it.
 *
 * Tapping a message makes it the one [onAnswer] answers; whatever it is, Twitch files the answer
 * under the same conversation. [input] is the field to write it in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadSheet(
    threadId: String,
    messages: StateFlow<List<ChatItem>>,
    style: ChatStyle,
    imageLoader: ImageLoader,
    partners: Map<String, ChannelMark>,
    onAnswer: (ChatItem) -> Unit,
    onDismiss: () -> Unit,
    input: @Composable () -> Unit,
) {
    val all by messages.collectAsStateWithLifecycle()
    val thread = remember(all, threadId) { all.filter { it.id == threadId || it.reply?.threadId == threadId } }
    // The first message may have left the buffer already. Every direct answer still quotes it.
    val lostRoot = remember(thread, threadId) {
        if (thread.firstOrNull()?.id == threadId) null
        else thread.firstNotNullOfOrNull { item -> item.reply?.takeIf { it.parentId == threadId } }
    }
    val gesture = remember(onAnswer) { { item: ChatItem, _: MessageGesture -> onAnswer(item) } }

    // Straight up to full height: the field sits at the bottom, and a half-open sheet would put
    // it under the keyboard.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        // The sheet's own window: read out here, these would be the chat's behind it.
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        // The keyboard goes with the sheet, the moment it is on its way out. Left to itself it
        // outlives the sheet by a moment, over the chat, and the chat jumps when it finally goes.
        LaunchedEffect(sheetState) {
            snapshotFlow { sheetState.currentValue == SheetValue.Expanded && sheetState.targetValue == SheetValue.Hidden }
                .filter { it }
                .collect {
                    focus.clearFocus()
                    keyboard?.hide()
                }
        }
        Column {
            Text(
                stringResource(R.string.thread_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
            // Reversed like the chat, so that the list stays at its newest end as answers come in.
            LazyColumn(reverseLayout = true, modifier = Modifier.weight(1f, fill = false).fillMaxWidth()) {
                val count = thread.size
                items(count = count, key = { thread[count - 1 - it].id }) { index ->
                    val item = thread[count - 1 - index]
                    MessageRow(item, style, imageLoader, gesture, channel = item.sourceRoomId?.let(partners::get))
                }
                lostRoot?.let { root -> item(key = "root") { LostRoot(root, style) } }
            }
            input()
        }
    }
}

/** What is left of a first message that is no longer in the buffer: the quote its answers carry. */
@Composable
private fun LostRoot(root: ReplyInfo, style: ChatStyle) {
    Text(
        text = "${style.nameOf(root.parentLogin, root.parentDisplayName)}: ${root.parentBody}",
        color = style.secondaryText,
        fontSize = style.fontSize.sp,
        fontStyle = FontStyle.Italic,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
