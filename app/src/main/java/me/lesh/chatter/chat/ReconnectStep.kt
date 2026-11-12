package me.lesh.chatter.chat

/** What the chat is doing after its connection dropped, until it has caught up again. */
enum class ReconnectStep { Connecting, WaitingForNetwork, LoadingHistory }

/** The history after a reconnect brought messages to [channel]; [oldest] is when the first was written. */
data class GapFilled(val channel: String, val oldest: Long)
