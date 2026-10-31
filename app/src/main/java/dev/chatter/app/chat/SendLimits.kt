package dev.chatter.app.chat

/** What keeps the user from writing freely, so the input can say so before Twitch does. */
enum class ChatRestriction { SubsOnly, FollowersOnly, EmoteOnly }

/** Twitch keeping the user out of a channel's chat, as the input shows it. */
sealed interface Penalty {
    /** [until] is epoch milliseconds; the timeout ends on its own then. */
    data class TimedOut(val until: Long) : Penalty

    /** Until a moderator lifts it, which Twitch does not announce; a message that gets through ends it. */
    data object Banned : Penalty
}

/**
 * Twitch's message limits, checked before sending. Twitch enforces them itself; this only spares
 * the user writing a message to find out.
 */
object SendLimits {
    /** Twitch's maximum message length, in characters as a person counts them. */
    const val MAX_LENGTH = 500

    /** From here on the input shows the remaining characters. */
    const val COUNTER_FROM = 400

    /** Characters as Twitch counts them: an emoji of two UTF-16 units is one. */
    fun length(text: String): Int = text.codePointCount(0, text.length)

    /**
     * Moderators, VIPs and the broadcaster bypass slow, followers-only and subscriber-only mode.
     */
    private fun exempt(role: ChatRole?) = role != null && role != ChatRole.Viewer

    /** Seconds the user has to wait after a message here; 0 if none. */
    fun slowSeconds(state: RoomState?, role: ChatRole?): Int =
        if (state == null || exempt(role)) 0 else state.slow

    /**
     * The mode that may keep the user from writing here, or null. Following cannot be seen from the
     * chat, so followers-only is shown to everyone it may affect.
     */
    fun restriction(state: RoomState?, role: ChatRole?, subscribed: Boolean): ChatRestriction? = when {
        state == null -> null
        state.subsOnly && !subscribed && !exempt(role) -> ChatRestriction.SubsOnly
        // Subscribers practically always follow too.
        state.followersOnly >= 0 && !subscribed && !exempt(role) -> ChatRestriction.FollowersOnly
        // Only those who can delete messages bypass emote-only.
        state.emoteOnly && role != ChatRole.Moderator && role != ChatRole.Broadcaster -> ChatRestriction.EmoteOnly
        else -> null
    }
}
