package dev.chatter.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomStateTest {
    @Test
    fun fullStateThenPartialUpdate() {
        val full = RoomState().update(
            mapOf("emote-only" to "0", "followers-only" to "10", "r9k" to "0", "slow" to "30", "subs-only" to "0")
        )
        assertEquals(RoomState(followersOnly = 10, slow = 30), full)
        // Twitch sends only the changed tag when a mode is toggled.
        val slowOff = full.update(mapOf("slow" to "0"))
        assertEquals(RoomState(followersOnly = 10), slowOff)
        assertTrue(slowOff.update(mapOf("followers-only" to "-1")).isDefault)
    }

    @Test
    fun roleFromBadges() {
        assertEquals(ChatRole.Broadcaster, ChatRole.fromBadges("broadcaster/1,subscriber/0"))
        assertEquals(ChatRole.Moderator, ChatRole.fromBadges("moderator/1"))
        assertEquals(ChatRole.Vip, ChatRole.fromBadges("vip/1,premium/1"))
        assertEquals(ChatRole.Viewer, ChatRole.fromBadges(""))
    }
}
