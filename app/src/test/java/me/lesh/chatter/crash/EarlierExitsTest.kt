package me.lesh.chatter.crash

import me.lesh.chatter.debug.AppLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A hang Android reports, as it goes into the crashes. */
class EarlierExitsTest {
    @get:Rule val folder = TemporaryFolder()

    private val device = DeviceInfo("0.15.0", 17, "17", 37, "Google Pixel 11")

    @Test
    fun theVersionIsTheEndedRunsOwn() {
        fun entry(message: String) = AppLog.Entry(0, 0, AppLog.Level.Info, "App", "main", message, message)
        val log = listOf(
            entry("Started Chatter 0.14.0 (16), process 123, 80 of 256 MB"),
            entry("Ready after 300 ms"),
        )
        assertEquals("0.14.0 (16)", EarlierExits.versionIn(log))
        assertNull(EarlierExits.versionIn(log.drop(1)))
    }

    private val dump = """
        ----- pid 4242 at 2026-11-20 10:00:00.000 -----
        Cmd line: me.lesh.chatter

        "Signal Catcher" daemon prio=10 tid=2 Runnable
          native: #00 pc 000000000004bd8c  /apex/com.android.runtime/lib64/bionic/libc.so

        "OkHttp https://www.twitch.tv/somechannel" prio=5 tid=20 Waiting
          at java.lang.Object.wait(Native method)

        "DefaultDispatcher-worker-1" prio=5 tid=12 Blocked
          at me.lesh.chatter.chat.MessageBuffers.add(MessageBuffers.kt:120)

        "main" prio=5 tid=1 Blocked
          at me.lesh.chatter.ui.chat.ChatList.render(ChatList.kt:300)
          - waiting to lock <0x0abc> held by thread 12

        ----- end 4242 -----
    """.trimIndent()

    @Test
    fun aHangShowsTheMainThreadFirstThenChattersOwn() {
        val text = AnrTrace.shorten(dump)
        assertTrue(text.startsWith("\"main\""))
        assertTrue(text.indexOf("DefaultDispatcher-worker-1") < text.indexOf("Signal Catcher"))
        assertFalse("thread names are cleaned", text.contains("somechannel"))
        assertFalse(text.contains("Cmd line"))
    }

    @Test
    fun aLongHangIsCutAndSaysHowManyThreadsAreLeft() {
        val text = AnrTrace.shorten(dump, budget = 150)
        assertTrue(text.startsWith("\"main\""))
        assertTrue(text, text.endsWith("… 3 more threads"))
    }

    @Test
    fun withoutStacksOnlyTheOpeningLinesStay() {
        val text = AnrTrace.shorten("Subject: Input dispatching timed out\nRssKb: 314348\n\n\n--- CriticalEventLog ---\nevents < other.app ANR >")
        assertEquals("Subject: Input dispatching timed out\nRssKb: 314348", text)
    }

    @Test
    fun aHangIsKeptLikeACrashWithItsKind() {
        val crashes = CrashLog(folder.root.resolve("crashes"), device)
        crashes.writeExit(1_000L, Crash.HANG, "ANR: Input dispatching timed out\n\"main\" prio=5", version = "0.14.0 (16)", log = listOf("10:00:00.000 I Chat line"))
        // Read again on the next start, it is not written twice.
        crashes.writeExit(1_000L, Crash.HANG, "other", version = null, log = emptyList())
        val crash = crashes.all().single()
        assertTrue(crash.isHang)
        assertEquals("ANR", crash.errorKind)
        assertEquals("0.14.0 (16)", crash.header["Chatter"])
        assertEquals("10:00:00.000 I Chat line", crash.log)
        assertEquals(Crash.HANG, CrashReport.forIssue(crash).title)
    }
}
