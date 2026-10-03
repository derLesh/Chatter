package dev.chatter.app.chat

import dev.chatter.app.irc.ConnectionState
import dev.chatter.app.irc.IrcMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The chat connection as the chat uses it: incoming messages, sending and state. Opening and
 * closing it is up to the app. An interface so the chat can be tested without a Twitch socket.
 */
interface ChatConnection {
    val messages: Flow<IrcMessage>
    val state: StateFlow<ConnectionState>

    fun join(channel: String)
    fun part(channel: String)

    /** False if the message could not be handed to the connection. */
    fun sendMessage(channel: String, text: String, replyParentId: String? = null): Boolean
}

/** Lines the chat writes itself, in the user's language. */
interface ChatNotices {
    fun chatCleared(): String
    fun timeout(name: String, seconds: Int): String
    fun ban(name: String): String
}

/** Counting while messages arrive; implemented by [dev.chatter.app.stats.StatsRepository]. */
interface ChatStats {
    fun countReceived(channel: String)
    fun countMention()
    fun countSent(channel: String)
}
