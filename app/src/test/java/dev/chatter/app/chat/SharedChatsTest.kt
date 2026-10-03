package dev.chatter.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared Chat sessions and partners, derived from messages and Helix alone. */
class SharedChatsTest {
    private val shared = SharedChats()

    @Test
    fun theFirstTaggedMessageStartsASessionAndAnUntaggedOneEndsIt() {
        assertFalse(shared.onLiveMessage("forsen", "1", null))
        assertEquals(emptyMap<String, List<String>>(), shared.sessions.value)

        // The channel's own message naming itself: a session, partners unknown.
        assertTrue(shared.onLiveMessage("forsen", "1", "1"))
        assertEquals(mapOf("forsen" to emptyList<String>()), shared.sessions.value)

        // A partner writes: it is known now, and no second session starts.
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

        // An empty answer means Helix lags behind, not that the session ended.
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
