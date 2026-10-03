package dev.chatter.app.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What a crash leaves behind for a bug report. */
class CrashLogTest {
    @get:Rule val folder = TemporaryFolder()

    private val device = DeviceInfo("0.8.0", 8, "16", 36, "Google Pixel 9")
    private fun log() = CrashLog(folder.root.resolve("crashes"), device)

    @Test
    fun aCrashSaysWhereItHappenedAndWhat() {
        val text = CrashLog.format(0L, "main", IllegalStateException("broken"), device)
        val head = text.lines().take(5)
        assertEquals(
            listOf(
                "Time: 1970-01-01 00:00:00 UTC",
                "Chatter: 0.8.0 (8)",
                "Android: 16 (API 36)",
                "Device: Google Pixel 9",
                "Thread: main",
            ),
            head,
        )
        assertTrue(text.contains("java.lang.IllegalStateException: broken"))
        assertTrue("the trace says where", text.contains("at dev.chatter.app.crash.CrashLogTest"))
    }

    @Test
    fun aVeryLongTraceIsCut() {
        var deep: Throwable = RuntimeException("x".repeat(20_000))
        repeat(3) { deep = RuntimeException("wrapped", deep) }
        val text = CrashLog.format(0L, "main", deep, device)
        assertTrue(text.length < 13_000)
        assertTrue(text.trimEnd().endsWith("(cut)"))
    }

    @Test
    fun theLatestCrashIsTheOneOffered() {
        val log = log()
        assertNull("nothing has crashed yet", log.latest())
        log.write("main", RuntimeException("first"), at = 1_000)
        log.write("worker", RuntimeException("second"), at = 2_000)
        val latest = log.latest()!!
        assertEquals(2_000L, latest.at)
        assertTrue(latest.text.contains("second"))
    }

    @Test
    fun onlyTheLastFewAreKept() {
        val log = log()
        (1..5).forEach { log.write("main", RuntimeException("crash $it"), at = it * 1_000L) }
        assertEquals(CrashLog.KEPT, folder.root.resolve("crashes").listFiles()!!.size)
        assertTrue(log.latest()!!.text.contains("crash 5"))
    }
}
