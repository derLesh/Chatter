package dev.chatter.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a channel shares its chat and with whom, read off nothing but the messages and Helix. */
class SharedChatsTest {
    private val shared = SharedChats()

    @Test
    fun theFirstTaggedMessageStartsASessionAndAnUntaggedOneEndsIt() {
        assertFalse(shared.onLiveMessage("forsen", "1", null))
        assertEquals(emptyMap<String, List<String>>(), shared.sessions.value)

        // The channel's own message, naming the channel itself: a session, partners unknown yet.
        assertTrue(shared.onLiveMessage("forsen", "1", "1"))
        assertEquals(mapOf("forsen" to emptyList<String>()), shared.sessions.value)

        // A partner writes: now it is known, and nothing has started a second time.
        assertFalse(shared.onLiveMessage("forsen", "1", "2"))
        assertFalse(shared.onLiveMessage("forsen", "1", "2"))
        assertEquals(mapOf("forsen" to listOf("2")), shared.sessions.value)

        assertFalse(shared.onLiveMessage("forsen", "1", null))
        assertEquals(emptyMap<String, List<String>>(), shared.sessions.value)
    }

    @Test
    fun helixNamesThePartnersThatHaveNotWrittenYet() {
        shared.onLiveMessage("forsen", "1", "2")
        shared.setParticipants("forsen", "1", listOf("1", "2", "3"))
        assertEquals(mapOf("forsen" to listOf("2", "3")), shared.sessions.value)

        // An empty answer is Helix being behind the messages, not the session being over.
        shared.setParticipants("forsen", "1", emptyList())
        assertEquals(mapOf("forsen" to listOf("2", "3")), shared.sessions.value)
    }

    @Test
    fun anAnswerForASessionThatEndedMeanwhileIsDropped() {
        shared.onLiveMessage("forsen", "1", "2")
        shared.onLiveMessage("forsen", "1", null)
        shared.setParticipants("forsen", "1", listOf("1", "2"))
        assertEquals(emptyMap<String, List<String>>(), shared.sessions.value)
    }

    @Test
    fun aPartnerIsLookedUpOnceUnlessTheLookupFailed() {
        assertEquals(listOf("2", "3"), shared.unknown(listOf("2", "3")))
        assertEquals(emptyList<String>(), shared.unknown(listOf("2")))

        shared.failed(listOf("3"))
        assertEquals(listOf("3"), shared.unknown(listOf("2", "3")))

        shared.described(listOf(ChatPartner("4", "four", "Four", null)))
        assertEquals(emptyList<String>(), shared.unknown(listOf("4")))
    }
}
