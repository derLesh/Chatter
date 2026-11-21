package me.lesh.chatter.crash

import me.lesh.chatter.debug.LogReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A crash put into a GitHub issue link. */
class CrashReportTest {
    private val device = DeviceInfo("0.10.0", 10, "16", 36, "Google Pixel 9")

    private fun crash(error: Throwable, log: List<String> = emptyList()) =
        Crash(1_000L, CrashLog.format(1_000L, "main", error, device, log))

    private fun encoded(text: String) = LogReport.uriEncodedLength(text)

    @Test
    fun aShortCrashGoesInWhole() {
        val crash = crash(IllegalStateException("broken"), log = listOf("line one", "line two"))
        val issue = CrashReport.forIssue(crash)
        assertTrue(issue.whole)
        assertEquals("Crash: IllegalStateException", issue.title)
        assertTrue(issue.crash.startsWith("Time: 1970-01-01 00:00:01 UTC"))
        assertTrue(issue.crash.contains("java.lang.IllegalStateException: broken"))
        assertEquals("line one\nline two", issue.log)
    }

    @Test
    fun aDeepTraceKeepsEveryCauseAndFitsTheLink() {
        fun deep(depth: Int, error: () -> Throwable): Throwable = if (depth == 0) error() else deep(depth - 1, error)
        val root = deep(150) { IllegalArgumentException("the real cause") }
        val crash = crash(RuntimeException("outer", root), log = (1..300).map { "log line $it with a few words" })
        val issue = CrashReport.forIssue(crash, maxEncodedChars = 6_000)
        assertFalse(issue.whole)
        assertTrue(encoded(issue.crash) + encoded(issue.log) <= 6_000)
        assertTrue(issue.crash.contains("java.lang.RuntimeException: outer"))
        assertTrue("the cause stays", issue.crash.contains("Caused by: java.lang.IllegalArgumentException: the real cause"))
        assertTrue(issue.crash.contains(" more"))
        assertTrue("the newest log line stays", issue.log.endsWith("log line 300 with a few words"))
        assertTrue(issue.log.contains("The whole crash is in the clipboard"))
    }

    @Test
    fun aHugeMessageIsCutToFit() {
        val crash = crash(RuntimeException("x".repeat(20_000)))
        val issue = CrashReport.forIssue(crash, maxEncodedChars = 3_000)
        assertTrue(encoded(issue.crash) <= 3_000)
        assertTrue(issue.crash.contains("java.lang.RuntimeException: xxx"))
    }
}
