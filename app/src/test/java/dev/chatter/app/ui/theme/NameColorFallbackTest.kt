package dev.chatter.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameColorFallbackTest {
    /** Its hash is Int.MIN_VALUE, whose absolute value is still negative. */
    @Test
    fun aLoginWhoseHashIsTheSmallestIntStillGetsAColor() {
        assertEquals(Int.MIN_VALUE, "polygenelubricants".hashCode())
        assertTrue(fallbackColorIndex("polygenelubricants", 15) in 0 until 15)
    }

    @Test
    fun everyLoginLandsInThePalette() {
        listOf("lesh", "forsen", "", null, "a".repeat(25), "zzzzzzzzzzzzzzzzzzzzzzzzz").forEach { login ->
            assertTrue("$login", fallbackColorIndex(login, 15) in 0 until 15)
        }
    }

    /** The same login keeps the same color, as it does on Twitch. */
    @Test
    fun theColorStaysTheSameForALogin() {
        assertEquals(fallbackColorIndex("lesh", 15), fallbackColorIndex("lesh", 15))
    }
}
