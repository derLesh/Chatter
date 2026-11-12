package me.lesh.chatter.chat

/**
 * What the pill over the chat says. [Connecting]: the first connect after the start; [LoadingMissed]:
 * the gap after a reconnect; [LoadingHistory]: the recent messages of a channel that just opened;
 * [HistoryFailed]: those could not be loaded, a warning shown for a moment.
 */
enum class ChatStatus { Connecting, Reconnecting, WaitingForNetwork, LoadingMissed, LoadingHistory, HistoryFailed }

/** The history after a reconnect brought messages to [channel]; [oldest] is when the first was written. */
data class GapFilled(val channel: String, val oldest: Long)
