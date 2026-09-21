package dev.chatter.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomsTest {
    private val rooms = Rooms()

    @Test
    fun theFirstRoomStateBringsTheIdAndSaysTheChannelIsReady() {
        assertTrue("first time", rooms.onRoomState("forsen", mapOf("room-id" to "22484632", "slow" to "30")))
        assertEquals("22484632", rooms.id("forsen"))
        assertEquals("forsen", rooms.channelOf("22484632"))
        assertTrue(rooms.ready.value.contains("forsen"))
        assertEquals(30, rooms.states.value["forsen"]?.slow)
    }

    @Test
    fun aLaterRoomStateOnlyChangesWhatItMentions() {
        rooms.onRoomState("forsen", mapOf("room-id" to "22484632", "slow" to "30", "subs-only" to "1"))
        assertFalse("the id was known already", rooms.onRoomState("forsen", mapOf("slow" to "0")))
        val state = rooms.states.value["forsen"]
        assertEquals(0, state?.slow)
        assertTrue("subs-only was not mentioned and stays", state?.subsOnly == true)
    }

    @Test
    fun theBadgesOfAChannelSayWhatTheUserIsThere() {
        rooms.onUserState("forsen", mapOf("badges" to "moderator/1,subscriber/12"))
        rooms.onUserState("xqc", mapOf("badges" to "subscriber/3"))
        assertEquals(ChatRole.Moderator, rooms.roles.value["forsen"])
        assertEquals(ChatRole.Viewer, rooms.roles.value["xqc"])
        assertEquals(setOf("forsen"), rooms.moderated.value)
        assertTrue(rooms.isPrivileged("forsen"))
        assertFalse(rooms.isPrivileged("xqc"))
    }

    @Test
    fun losingTheBadgeGivesUpTheRoleAgain() {
        rooms.onUserState("forsen", mapOf("badges" to "moderator/1"))
        rooms.onUserState("forsen", mapOf("badges" to ""))
        assertEquals(ChatRole.Viewer, rooms.roles.value["forsen"])
        assertEquals(emptySet<String>(), rooms.moderated.value)
    }

    @Test
    fun aChannelThatHasNotAnsweredYetSendsUnderTheGlobalBadges() {
        rooms.onGlobalUserState(mapOf("badges" to "premium/1", "color" to "#00FF00"))
        assertEquals("#00FF00", rooms.userState("forsen")["color"])

        rooms.onUserState("forsen", mapOf("badges" to "vip/1"))
        assertEquals("vip/1", rooms.userState("forsen")["badges"])
        assertEquals("the global ones still stand in elsewhere", "premium/1", rooms.userState("xqc")["badges"])
    }

    @Test
    fun leavingAChannelForgetsWhatTwitchSaidAboutIt() {
        rooms.onRoomState("forsen", mapOf("room-id" to "22484632", "slow" to "30"))
        rooms.onUserState("forsen", mapOf("badges" to "moderator/1"))
        rooms.forget("forsen")
        assertNull(rooms.states.value["forsen"])
        assertNull(rooms.roles.value["forsen"])
        assertEquals(emptySet<String>(), rooms.moderated.value)
        assertEquals(emptySet<String>(), rooms.ready.value)
        // The id is a fact about the channel and costs a request, so it is kept.
        assertEquals("22484632", rooms.id("forsen"))
    }

    @Test
    fun loggingOutForgetsWhoTheUserWasButNotWhichChannelIsWhich() {
        rooms.onRoomState("forsen", mapOf("room-id" to "22484632"))
        rooms.onUserState("forsen", mapOf("badges" to "moderator/1"))
        rooms.onGlobalUserState(mapOf("badges" to "premium/1"))
        rooms.clear()
        assertEquals(emptyMap<String, ChatRole>(), rooms.roles.value)
        assertTrue(rooms.userState("forsen").isEmpty())
        assertEquals(listOf("22484632"), rooms.knownIds())
    }
}
