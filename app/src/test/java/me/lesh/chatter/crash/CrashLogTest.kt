package me.lesh.chatter.crash

import me.lesh.chatter.debug.AppLog
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
        assertTrue("the trace says where", text.contains("at me.lesh.chatter.crash.CrashLogTest"))
    }

    @Test
    fun aVeryLongTraceIsCut() {
        var deep: Throwable = RuntimeException("x".repeat(20_000))
        repeat(3) { deep = RuntimeException("wrapped", deep) }
        val text = CrashLog.format(0L, "main", deep, device)
        assertTrue(text.length < 13_000)
        assertTrue("every cause stays", text.contains("Caused by: java.lang.RuntimeException: xxx"))
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
            // Links keep only their host, like in the private log.
            assertTrue(text.contains("java.lang.RuntimeException: GET https://id.twitch.tv/… failed"))
            assertTrue(text.contains("Authorization=[hidden], Client-Id=abc"))
            assertTrue(text.contains("Caused by: java.lang.IllegalArgumentException: http://localhost/…"))
            assertTrue(text.contains("Caused by: java.lang.IllegalStateException: PASS oauth:[hidden] was refused"))
            assertTrue(text.contains("Suppressed: java.lang.RuntimeException: {\"refresh_token\":\"[hidden]\",\"password\": \"[hidden]\"}"))
            assertTrue(text.contains("at me.lesh.chatter.crash.CrashLogTest.leakyError"))
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

    @Test
    fun aCrashKeepsTheLogBeforeIt() {
        val log = log()
        log.write("main", IllegalStateException("broken"), at = 1_000, log = listOf("line one", "PASS oauth:${secrets[0]}"))
        val crash = log.latest()!!
        assertEquals("0.8.0 (8)", crash.header["Chatter"])
        assertEquals("Google Pixel 9", crash.header["Device"])
        assertEquals("java.lang.IllegalStateException: broken", crash.error)
        assertEquals("IllegalStateException", crash.errorKind)
        assertTrue(crash.trace.startsWith("java.lang.IllegalStateException: broken"))
        assertFalse("the log is not part of the trace", crash.trace.contains("line one"))
        assertEquals(listOf("line one", "PASS oauth:[hidden]"), crash.log.lines())
    }

    @Test
    fun aCrashFromBeforeTheLogHasNone() {
        val dir = folder.root.resolve("crashes").apply { mkdirs() }
        dir.resolve("crash-1000.txt").writeText("Time: x\nChatter: 0.9.0 (9)\n\njava.lang.Error: old\n\tat a.b.C.d(C.kt:1)\n")
        val crash = log().latest()!!
        assertEquals("", crash.log)
        assertEquals("java.lang.Error: old\n\tat a.b.C.d(C.kt:1)", crash.trace)
    }

    @Test
    fun theLogTailKeepsTheNewestLines() {
        val entries = (1L..1_000L).map { AppLog.Entry(it, 0L, AppLog.Level.Info, "Chat", "main", "line $it", "line $it") }
        val tail = CrashLog.logTail(entries)
        assertTrue(tail.size in 1..200)
        assertTrue(tail.last().endsWith("line 1000"))
        assertTrue(tail.sumOf { it.length + 1 } <= 30_000)
    }

    @Test
    fun allListsTheNewestFirstAndDeleteTakesOne() {
        val log = log()
        (1..3).forEach { log.write("main", RuntimeException("crash $it"), at = it * 1_000L) }
        assertEquals(listOf(3_000L, 2_000L, 1_000L), log.all().map { it.at })
        log.delete(2_000L)
        assertEquals(listOf(3_000L, 1_000L), log.all().map { it.at })
        log.clear()
        assertTrue(log.all().isEmpty())
    }

    @Test
    fun aCrashIsOfferedOnceAndOnlyWhileRecent() {
        val log = log()
        val day = 24 * 60 * 60 * 1000L
        log.write("main", RuntimeException("old"), at = 1_000)
        assertNull("too old to ask about", log.unoffered(now = 1_000 + 4 * day))
        log.write("main", RuntimeException("new"), at = 2_000)
        assertEquals(2_000L, log.unoffered(now = 3_000)?.at)
        log.markOffered(2_000, now = 3_000)
        assertNull(log.unoffered(now = 3_000))
        log.write("main", RuntimeException("again"), at = 4_000)
        assertNull("a crash on every start asks once a day", log.unoffered(now = 5_000))
        assertEquals(4_000L, log.unoffered(now = 3_000 + day)?.at)
    }

    @Test
    fun linksAndThreadNamesKeepOnlyTheHost() {
        val text = CrashLog.format(0L, "OkHttp https://api.twitch.tv/helix/users?login=somebody", RuntimeException("GET https://7tv.io/v3/users/twitch/123 failed"), device)
        assertFalse(text.contains("somebody"))
        assertFalse(text.contains("/v3/users"))
        assertTrue(text.contains("Thread: OkHttp https://api.twitch.tv/…"))
        assertTrue(text.contains("at me.lesh.chatter.crash.CrashLogTest"))
    }

    @Test
    fun aHalfWrittenCrashIsNotListed() {
        val dir = folder.root.resolve("crashes").apply { mkdirs() }
        dir.resolve("crash-1000.partial").writeText("Time: x")
        assertTrue(log().all().isEmpty())
        log().clear()
        assertTrue("clearing takes it along", dir.listFiles()!!.isEmpty())
    }

    @Test
    fun anErrorPageOverSeveralLinesIsLeftOut() {
        val page = IllegalStateException("HTTP 404: {\"error\":\n\"no user somebody\",\n\"said\": \"hello chat\"}")
        val text = CrashLog.format(0L, "main", RuntimeException("load failed", page), device)
        assertFalse(text.contains("somebody"))
        assertFalse(text.contains("hello chat"))
        assertTrue(text.contains("Caused by: java.lang.IllegalStateException: HTTP 404"))
        assertTrue("the frames below stay", text.substringAfter("HTTP 404").contains("at me.lesh.chatter.crash.CrashLogTest"))
    }

    @Test
    fun aLongTraceKeepsItsRootCause() {
        fun deep(depth: Int, error: () -> Throwable): Throwable = if (depth == 0) error() else deep(depth - 1, error)
        val outer = deep(400) { RuntimeException("outer", IllegalArgumentException("the root cause")) }
        val text = CrashLog.format(0L, "main", outer, device)
        assertTrue(text.length < 13_000)
        assertTrue(text.contains("Caused by: java.lang.IllegalArgumentException: the root cause"))
    }
}
