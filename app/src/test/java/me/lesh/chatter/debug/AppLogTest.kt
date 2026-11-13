package me.lesh.chatter.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals("line 5", entries.first().private)
        assertEquals("line ${AppLog.KEPT + 4}", entries.last().private)
    }

    @Test
    fun linesAreCleanedBeforeTheyAreKept() {
        AppLog.w("Test", "Failed: https://api.ivr.fi/v2/twitch/user?login=somebody")
        assertEquals("Failed: https://api.ivr.fi/…", AppLog.entries().single().private)
    }

    @Test
    fun anErrorSaysItsKindAndWhereInChatter() {
        AppLog.e("Test", "Broke", IllegalStateException("bad state"))
        val message = AppLog.entries().single().private
        assertTrue(message, message.startsWith("Broke: java.lang.IllegalStateException: bad state (at AppLogTest."))
    }

    @Test
    fun aChannelKeepsItsStandInInThePrivateLogAndItsNameInTheFullOne() {
        AppLog.i("Test", "Join ${AppLog.channel("SomeStreamer")}")
        AppLog.i("Test", "Join ${AppLog.channel("somestreamer")}")
        AppLog.i("Test", "Join ${AppLog.channel("another_one")}")
        val (first, again, other) = AppLog.entries()
        assertTrue(first.private, first.private.matches(Regex("""Join channel \d+""")))
        assertEquals(first.private, again.private)
        assertNotEquals(first.private, other.private)
        assertEquals("Join #somestreamer", first.full)
    }

    @Test
    fun theFullLogKeepsLinksAndErrorPagesButNeverTheLogin() {
        AppLog.w("Test", "GET ${AppLog.hidden("https://api.ivr.fi/v2/twitch/user?login=somebody", "api.ivr.fi")} → HTTP 404: no such user")
        AppLog.w("Test", "Asked with access_token=secret123")
        val (link, token) = AppLog.entries()
        assertEquals("GET api.ivr.fi → HTTP 404", link.private)
        assertEquals("GET https://api.ivr.fi/v2/twitch/user?login=somebody → HTTP 404: no such user", link.full)
        assertFalse(token.full, token.full.contains("secret123"))
    }
}
