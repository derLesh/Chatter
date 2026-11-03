package me.lesh.chatter.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The chat modes a mod can pick from the input, and what Helix needs to switch to them. */
object ChatModes {
    /** The waits Twitch's own chat offers, in seconds. */
    val SLOW_SECONDS = listOf(3, 5, 10, 20, 30, 60, 120)

    /** How long one must follow, in minutes: none, 10 minutes up to 3 months, as on Twitch. */
    val FOLLOW_MINUTES = listOf(0, 10, 30, 60, 1440, 10_080, 43_200, 129_600)

    /** The chat settings body that turns [from] into [to]; only what differs is sent. */
    fun changes(from: RoomState, to: RoomState): JsonObject = buildJsonObject {
        if (to.slow != from.slow) {
            put("slow_mode", to.slow > 0)
            if (to.slow > 0) put("slow_mode_wait_time", to.slow)
        }
        if (to.followersOnly != from.followersOnly) {
            put("follower_mode", to.followersOnly >= 0)
            if (to.followersOnly >= 0) put("follower_mode_duration", to.followersOnly)
        }
        if (to.subsOnly != from.subsOnly) put("subscriber_mode", to.subsOnly)
        if (to.emoteOnly != from.emoteOnly) put("emote_mode", to.emoteOnly)
        if (to.uniqueChat != from.uniqueChat) put("unique_chat_mode", to.uniqueChat)
    }
}
