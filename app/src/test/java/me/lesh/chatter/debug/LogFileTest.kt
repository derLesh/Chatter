package me.lesh.chatter.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One log line as it is stored on the phone, and back. */
class LogFileTest {
    @Test
    fun aLineComesBackAsItWent() {
        val entry = AppLog.Entry(7, 1_700_000_000_000, AppLog.Level.Warn, "Http", "OkHttp worker", "a\ttab and\nline \\ here", "full #name")
        val back = LogFile.decode(LogFile.encode(entry), number = -1)!!
        assertEquals(-1L, back.number)
        assertEquals(entry.at, back.at)
        assertEquals(entry.level, back.level)
        assertEquals(entry.tag, back.tag)
        assertEquals(entry.thread, back.thread)
        assertEquals(entry.private, back.private)
        assertEquals(entry.full, back.full)
    }

    @Test
    fun aBrokenLineIsSkipped() {
        assertNull(LogFile.decode("not a log line", number = 0))
        assertNull(LogFile.decode("x\tW\tHttp\tmain\ta\tb", number = 0))
    }
}
