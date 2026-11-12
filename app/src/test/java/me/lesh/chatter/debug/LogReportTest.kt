package me.lesh.chatter.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

/** The log as it is copied, saved and put into a bug report. */
class LogReportTest {
    private val device = listOf("Chatter" to "0.9.0 (9), release", "Android" to "16 (API 36)")
    private fun entry(number: Long, message: String) = AppLog.Entry(number, 0L, AppLog.Level.Warn, "Http", message)

    @Test
    fun theDeviceComesFirstThenOneLinePerEntry() {
        val text = LogReport.text(device, listOf(entry(1, "first"), entry(2, "second")))
        assertEquals(
            listOf(
                "Chatter: 0.9.0 (9), release",
                "Android: 16 (API 36)",
                "",
                "1970-01-01 00:00:00.000Z W Http: first",
                "1970-01-01 00:00:00.000Z W Http: second",
            ),
            text.lines(),
        )
    }

    @Test
    fun aShortLogGoesIntoTheIssueWhole() {
        val issue = LogReport.forIssue(device, listOf(entry(1, "only")))
        assertTrue(issue.whole)
        assertTrue(issue.text.endsWith("W Http: only"))
    }

    @Test
    fun aLongLogKeepsTheDeviceAndTheNewestLines() {
        val entries = (1L..500L).map { entry(it, "line $it with some words to make it long") }
        val issue = LogReport.forIssue(device, entries, maxEncodedChars = 2_000)
        assertFalse(issue.whole)
        assertTrue(issue.text.startsWith("Chatter: 0.9.0 (9), release"))
        assertTrue(issue.text.endsWith("line 500 with some words to make it long"))
        assertFalse(issue.text.contains("line 1 with"))
        assertTrue(URLEncoder.encode(issue.text, Charsets.UTF_8).length <= 2_000)
    }
}
