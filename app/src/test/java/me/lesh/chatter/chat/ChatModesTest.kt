package me.lesh.chatter.chat

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatModesTest {
    @Test
    fun onlyWhatChangedIsSent() {
        val from = RoomState(subsOnly = true, slow = 30)
        val body = ChatModes.changes(from, from.copy(emoteOnly = true))
        assertEquals(buildJsonObject { put("emote_mode", JsonPrimitive(true)) }, body)
    }

    @Test
    fun slowModeCarriesItsWait() {
        val body = ChatModes.changes(RoomState(), RoomState(slow = 10))
        assertEquals(JsonPrimitive(true), body["slow_mode"])
        assertEquals(JsonPrimitive(10), body["slow_mode_wait_time"])
    }

    @Test
    fun turningSlowModeOffSendsNoWait() {
        val body = ChatModes.changes(RoomState(slow = 10), RoomState())
        assertEquals(buildJsonObject { put("slow_mode", JsonPrimitive(false)) }, body)
    }

    @Test
    fun followersWithoutAMinimumAreStillFollowersOnly() {
        val body = ChatModes.changes(RoomState(), RoomState(followersOnly = 0))
        assertEquals(JsonPrimitive(true), body["follower_mode"])
        assertEquals(JsonPrimitive(0), body["follower_mode_duration"])
    }

    @Test
    fun followersOffIsMinusOne() {
        val body = ChatModes.changes(RoomState(followersOnly = 10), RoomState())
        assertEquals(buildJsonObject { put("follower_mode", JsonPrimitive(false)) }, body)
    }

    @Test
    fun theSameStateChangesNothing() {
        assertTrue(ChatModes.changes(RoomState(uniqueChat = true), RoomState(uniqueChat = true)).isEmpty())
    }
}
