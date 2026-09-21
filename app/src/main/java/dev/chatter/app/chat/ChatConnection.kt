package dev.chatter.app.chat

import dev.chatter.app.irc.ConnectionState
import dev.chatter.app.irc.IrcMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The chat connection as the chat itself uses it: what comes in, what goes out, and how it is
 * doing. Opening and closing it is the app's business, not the chat's, and is not in here.
 *
 * An interface because the real one is a WebSocket to Twitch — there is no way to ask it what the
 * app does with a CLEARCHAT without somebody being banned somewhere.
 */
interface ChatConnection {
    val messages: Flow<IrcMessage>
    val state: StateFlow<ConnectionState>

    fun join(channel: String)
    fun part(channel: String)

    /** Returns false if the message could not be handed to the connection. */
    fun sendMessage(channel: String, text: String, replyParentId: String? = null): Boolean
}

/** The lines the chat writes by itself, in the user's language. */
interface ChatNotices {
    fun chatCleared(): String
    fun timeout(name: String, seconds: Int): String
    fun ban(name: String): String
}

/** The counting that happens while messages go by; [dev.chatter.app.stats.StatsRepository] does it. */
interface ChatStats {
    fun countReceived()
    fun countMention()
    fun countSent(channel: String)
}
