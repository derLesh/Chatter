package dev.chatter.app.ui.chat

import dev.chatter.app.chat.ChatItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** How long "Keep the screen on" holds the screen after the last message or touch. */
const val SCREEN_ON_AFTER_ACTIVITY_MS = 10 * 60_000L

/**
 * Whether to keep the screen on: true while the chat got a new message or a touch in the last
 * [quietAfterMs], then false until the next one. Starts true, for the chat just opened. A lost
 * connection brings no messages, so it counts as quiet.
 */
fun screenOnWhileChatMoves(
    messages: Flow<List<ChatItem>>,
    touches: Flow<Unit>,
    quietAfterMs: Long = SCREEN_ON_AFTER_ACTIVITY_MS,
): Flow<Boolean> = channelFlow {
    // The newest message, not the list: a message deleted or edited in place is no new one.
    val newMessages = messages.map { it.lastOrNull()?.id }.distinctUntilChanged().map { }
    merge(newMessages, touches).collectLatest {
        send(true)
        delay(quietAfterMs)
        send(false)
    }
}.distinctUntilChanged()
