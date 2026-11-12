package me.lesh.chatter.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The log in memory: what it keeps, how much, and the stand-ins for channel names. */
class AppLogTest {
    @Before
    fun startEmpty() = AppLog.clear()

    @Test
    fun keepsTheNewestLinesOnly() {
        repeat(AppLog.KEPT + 5) { AppLog.d("Test", "line $it") }
        val entries = AppLog.entries()
        assertEquals(AppLog.KEPT, entries.size)
        assertEquals("line 5", entries.first().message)
        assertEquals("line ${AppLog.KEPT + 4}", entries.last().message)
        assertEquals(AppLog.lastNumber.value, entries.last().number)
    }

    @Test
    fun linesAreCleanedBeforeTheyAreKept() {
        AppLog.w("Test", "Failed: https://api.ivr.fi/v2/twitch/user?login=somebody")
        assertEquals("Failed: https://api.ivr.fi/…", AppLog.entries().single().message)
    }

    @Test
    fun anErrorSaysItsKindAndWhereInChatter() {
        AppLog.e("Test", "Broke", IllegalStateException("bad state"))
        val message = AppLog.entries().single().message
        assertTrue(message, message.startsWith("Broke: IllegalStateException: bad state (at AppLogTest."))
    }

    @Test
    fun aChannelKeepsItsStandInAndNeverShowsItsName() {
        val first = AppLog.channel("SomeStreamer")
        assertEquals(first, AppLog.channel("somestreamer"))
        assertNotEquals(first, AppLog.channel("another_one"))
        assertTrue(first, first.matches(Regex("""channel \d+""")))
    }
}
