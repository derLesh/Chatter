package me.lesh.chatter.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.ZoneOffset

/** One log line as it is stored on the phone, and back. */
class LogFileTest {
    @get:Rule val folder = TemporaryFolder()

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

    @Test
    fun aDayReadsTheArchiveAndTheFileInOrderWithOneNumberEach() {
        val date = LocalDate.of(2026, 11, 19)
        val noon = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() + 12 * 60 * 60 * 1000L
        fun line(at: Long, message: String) = LogFile.encode(AppLog.Entry(0, at, AppLog.Level.Info, "Chat", "main", message, message))
        val archive = LogArchive(folder.root.resolve("days"), ZoneOffset.UTC)
        archive.add(listOf(line(noon, "archived"), line(noon + 1, "archived too")), now = noon)
        val file = folder.root.resolve("log.tsv").apply { writeText(line(noon + 2, "in the file") + "\n") }
        val log = LogFile(file, archive)
        log.open {}
        val day = log.day(date)
        assertEquals(listOf("archived", "archived too", "in the file"), day.map { it.private })
        assertEquals(day.size, day.map { it.number }.distinct().size)
    }
}
