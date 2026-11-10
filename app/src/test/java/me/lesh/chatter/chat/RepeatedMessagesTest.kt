package me.lesh.chatter.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatedMessagesTest {
    @Test
    fun theFirstSpaceIsDoubled() {
        assertEquals("gg  wp chat", RepeatedMessages.vary("gg wp chat"))
    }

    @Test
    fun aSingleWordGetsTheInvisibleEnding() {
        assertEquals("Pog ͏", RepeatedMessages.vary("Pog"))
    }

    @Test
    fun theSpaceAfterACommandIsLeftAlone() {
        // Twitch takes "/me " as the command, so doubling that space would change nothing.
        assertEquals("/me waves  hello", RepeatedMessages.vary("/me waves hello"))
        assertEquals("/me waves ͏", RepeatedMessages.vary("/me waves"))
        assertEquals(". .a  b", RepeatedMessages.vary(". .a b"))
        assertEquals(".hi ͏", RepeatedMessages.vary(".hi"))
    }

    @Test
    fun onlyTheSameLineWithinThirtySecondsIsARepeat() {
        assertTrue(RepeatedMessages.isRepeat("Pog", "Pog", sinceMs = 1_000))
        assertFalse(RepeatedMessages.isRepeat("Pog", "Pog", sinceMs = 30_000))
        assertFalse(RepeatedMessages.isRepeat("Pog", "KEKW", sinceMs = 1_000))
        assertFalse(RepeatedMessages.isRepeat("Pog", null, sinceMs = 1_000))
    }

    @Test
    fun aThirdCopyGoesOutPlainAgain() {
        // Each copy differs from the one before it, which is all Twitch compares.
        var previous: String? = null
        val sent = List(3) {
            val text = "gg wp"
            val outgoing = if (RepeatedMessages.isRepeat(text, previous, sinceMs = 0)) RepeatedMessages.vary(text) else text
            previous = outgoing
            outgoing
        }
        assertEquals(listOf("gg wp", "gg  wp", "gg wp"), sent)
    }
}
