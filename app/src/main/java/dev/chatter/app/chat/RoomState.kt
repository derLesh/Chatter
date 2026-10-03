package dev.chatter.app.chat

/** A channel's chat modes, from ROOMSTATE. */
data class RoomState(
    val emoteOnly: Boolean = false,
    /** Minimum follow time in minutes; -1 means followers-only is off. */
    val followersOnly: Int = -1,
    val uniqueChat: Boolean = false,
    /** Seconds between messages; 0 means slow mode is off. */
    val slow: Int = 0,
    val subsOnly: Boolean = false,
) {
    val isDefault: Boolean get() = this == RoomState()

    /** ROOMSTATE may contain only the changed tags, so it is applied on top of the old state. */
    fun update(tags: Map<String, String>): RoomState = copy(
        emoteOnly = tags["emote-only"]?.let { it == "1" } ?: emoteOnly,
        followersOnly = tags["followers-only"]?.toIntOrNull() ?: followersOnly,
        uniqueChat = tags["r9k"]?.let { it == "1" } ?: uniqueChat,
        slow = tags["slow"]?.toIntOrNull() ?: slow,
        subsOnly = tags["subs-only"]?.let { it == "1" } ?: subsOnly,
    )
}

/** The user's role in a channel, from the USERSTATE badges. */
enum class ChatRole(val badgeTag: String?) {
    Viewer(null), Vip("vip/1"), Moderator("moderator/1"), Broadcaster("broadcaster/1");

    companion object {
        fun fromBadges(badges: String): ChatRole = when {
            badges.contains("broadcaster/") -> Broadcaster
            badges.contains("moderator/") -> Moderator
            badges.contains("vip/") -> Vip
            else -> Viewer
        }
    }
}
