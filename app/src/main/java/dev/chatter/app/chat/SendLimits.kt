package dev.chatter.app.chat

/** What keeps the user from writing freely in a chat, so the field can say it before Twitch does. */
enum class ChatRestriction { SubsOnly, FollowersOnly, EmoteOnly }

/**
 * The limits Twitch puts on a message, worked out ahead of sending it. Twitch enforces all of
 * them itself and answers with a notice; knowing them here is only so that the user does not have
 * to write a message first to find out.
 */
object SendLimits {
    /** The longest message Twitch takes, in characters as a person counts them. */
    const val MAX_LENGTH = 500

    /** From here on the field shows how many characters are left. */
    const val COUNTER_FROM = 400

    /** Characters as Twitch counts them: an emoji made of two UTF-16 units is one. */
    fun length(text: String): Int = text.codePointCount(0, text.length)

    /** Moderators, VIPs and the broadcaster write past slow, followers-only and subscriber-only mode. */
    private fun exempt(role: ChatRole?) = role != null && role != ChatRole.Viewer

    /** How long the user has to wait after a message in this room; 0 when they do not have to. */
    fun slowSeconds(state: RoomState?, role: ChatRole?): Int =
        if (state == null || exempt(role)) 0 else state.slow

    /**
     * The mode that may keep the user from writing here, or null. Whether they follow the channel
     * cannot be seen from the chat, so followers-only is named to everyone it may concern.
     */
    fun restriction(state: RoomState?, role: ChatRole?, subscribed: Boolean): ChatRestriction? = when {
        state == null -> null
        state.subsOnly && !subscribed && !exempt(role) -> ChatRestriction.SubsOnly
        // Somebody subscribed to a channel as good as always follows it too.
        state.followersOnly >= 0 && !subscribed && !exempt(role) -> ChatRestriction.FollowersOnly
        // Only the ones who can delete a message are let past this one.
        state.emoteOnly && role != ChatRole.Moderator && role != ChatRole.Broadcaster -> ChatRestriction.EmoteOnly
        else -> null
    }
}
