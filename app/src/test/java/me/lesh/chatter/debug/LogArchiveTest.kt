package me.lesh.chatter.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.ZoneOffset

/** The days the log has moved past, compressed on the phone. */
class LogArchiveTest {
    @get:Rule val folder = TemporaryFolder()

    private val day = 24 * 60 * 60 * 1000L
    /** 2026-11-19 12:00 UTC. */
    private val noon = LocalDate.of(2026, 11, 19).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() + day / 2

    private fun archive() = LogArchive(folder.root.resolve("days"), ZoneOffset.UTC)

    private fun bytes() = folder.root.resolve("days").listFiles().orEmpty().sumOf { it.length() }

    private fun line(at: Long, message: String) =
        LogFile.encode(AppLog.Entry(0, at, AppLog.Level.Info, "Chat", "main", message, "$message #name"))

    @Test
    fun linesComeBackByDayInOrder() {
        val archive = archive()
        archive.add(listOf(line(noon - day, "yesterday"), line(noon, "first")), now = noon)
        archive.add(listOf(line(noon + 1, "second")), now = noon)
        assertEquals(listOf("first", "second"), archive.read(LocalDate.of(2026, 11, 19)).map { it.private })
        assertEquals(listOf("second #name"), archive.read(LocalDate.of(2026, 11, 19)).drop(1).map { it.full })
        assertEquals(listOf("yesterday"), archive.read(LocalDate.of(2026, 11, 18)).map { it.private })
        assertEquals(listOf(LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 18)), archive.days())
    }

    @Test
    fun onlyTheLastDaysAreKept() {
        val archive = archive()
        (0L..5L).forEach { back -> archive.add(listOf(line(noon - back * day, "day $back")), now = noon - back * day) }
        archive.add(listOf(line(noon, "today")), now = noon)
        assertEquals(LogArchive.DAYS_BACK + 1, archive.days().size.toLong())
        assertEquals(LocalDate.of(2026, 11, 16), archive.days().last())
    }

    @Test
    fun itTakesFarLessRoomThanTheText() {
        val archive = archive()
        val lines = (1..5_000).map { line(noon + it, "GET api.twitch.tv/helix/users → 200 (h2, 797 of 800 left) in ${it % 400} ms") }
        archive.add(lines, now = noon)
        val text = lines.sumOf { it.length + 1 }
        assertTrue("${bytes()} of $text", bytes() * 5 < text)
    }

    @Test
    fun aFullDayStopsGrowing() {
        val archive = archive()
        val noise = (1..2_000).map { line(noon + it, java.util.UUID.randomUUID().toString().repeat(4)) }
        repeat(20) { archive.add(noise, now = noon) }
        assertTrue(bytes() < LogArchive.MAX_DAY_BYTES + 400_000)
    }

    @Test
    fun aCutOffEndKeepsWhatCameBefore() {
        val archive = archive()
        archive.add(listOf(line(noon, "kept")), now = noon)
        folder.root.resolve("days/2026-11-19.gz").appendBytes(byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 0))
        assertEquals(listOf("kept"), archive.read(LocalDate.of(2026, 11, 19)).map { it.private })
    }

    @Test
    fun clearingTakesEveryDay() {
        val archive = archive()
        archive.add(listOf(line(noon - day, "a"), line(noon, "b")), now = noon)
        archive.clear()
        assertTrue(archive.days().isEmpty())
    }
}
