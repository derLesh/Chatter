package dev.chatter.app.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** Made up, but shaped like what Twitch hands out. */
    private val secrets = listOf("abcdefghijklmnopqrstuvwxyz0123", "zyxwvutsrqponmlkjihgfedcba9876", "refreshrefreshrefresh123", "hunter22")

    private fun leakyError(): Throwable {
        val cause = IllegalStateException("PASS oauth:${secrets[0]} was refused")
        cause.addSuppressed(RuntimeException("""{"refresh_token":"${secrets[2]}","password": "${secrets[3]}"}"""))
        return RuntimeException(
            "GET https://id.twitch.tv/oauth2/validate failed, headers {Authorization=Bearer ${secrets[1]}, Client-Id=abc}",
            IllegalArgumentException("http://localhost#access_token=${secrets[0]}&scope=chat%3Aread&state=x", cause),
        )
    }

    @Test
    fun loginSecretsNeverReachTheReport() {
        val log = log()
        log.write("main", leakyError(), at = 1_000)
        val stored = folder.root.resolve("crashes").listFiles()!!.single().readText()
        val copied = log.latest()!!.text
        for (text in listOf(stored, copied)) {
            secrets.forEach { assertFalse("$it is in the report", text.contains(it)) }
            // What helps find the bug stays.
            assertTrue(text.contains("java.lang.RuntimeException: GET https://id.twitch.tv/oauth2/validate failed"))
            assertTrue(text.contains("Authorization=[hidden], Client-Id=abc"))
            assertTrue(text.contains("Caused by: java.lang.IllegalArgumentException: http://localhost#access_token=[hidden]&scope=chat%3Aread"))
            assertTrue(text.contains("Caused by: java.lang.IllegalStateException: PASS oauth:[hidden] was refused"))
            assertTrue(text.contains("Suppressed: java.lang.RuntimeException: {\"refresh_token\":\"[hidden]\",\"password\": \"[hidden]\"}"))
            assertTrue(text.contains("at dev.chatter.app.crash.CrashLogTest.leakyError"))
        }
    }

    @Test
    fun aReportFromBeforeTheCleaningIsCleanedWhenOffered() {
        val dir = folder.root.resolve("crashes").apply { mkdirs() }
        dir.resolve("crash-1000.txt").writeText("java.io.IOException: Authorization: OAuth ${secrets[1]}\n\tat a.b.C.d(C.kt:1)\n")
        val copied = log().latest()!!.text
        assertFalse(copied.contains(secrets[1]))
        assertTrue(copied.contains("Authorization: [hidden]"))
        assertTrue(copied.contains("at a.b.C.d(C.kt:1)"))
    }

    @Test
    fun wordsThatOnlyLookLikeSecretsStay() {
        val text = "Invalid token at offset 3; password too short; tokenizer=fast"
        assertEquals(text, CrashLog.hideSecrets(text))
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
