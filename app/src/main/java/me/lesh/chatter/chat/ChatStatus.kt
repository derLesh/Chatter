package me.lesh.chatter.chat

/**
 * What the pill over the chat says. [LoadingMissed]: the gap after a reconnect; [LoadingHistory]:
 * the recent messages of a channel that just opened.
 */
enum class ChatStatus { Connecting, WaitingForNetwork, LoadingMissed, LoadingHistory }

/** The history after a reconnect brought messages to [channel]; [oldest] is when the first was written. */
data class GapFilled(val channel: String, val oldest: Long)
