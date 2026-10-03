package dev.chatter.app.irc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pacing a long channel list's JOINs within Twitch's limit. */
class JoinQueueTest {
    private var now = 1_000_000L
    private fun queue(rank: (String) -> Int = { 0 }) = JoinQueue({ now }, rank, limit = 20, windowMs = 10_000, answerTimeoutMs = 30_000)
    private fun channels(n: Int) = (1..n).map { "c$it" }

    @Test
    fun noMoreThanTwentyGoOutInTenSeconds() {
        val q = queue()
        q.restart(channels(45))
        assertEquals(20, q.take().size)
        assertTrue("the limit is used up", q.take().isEmpty())
        assertEquals(now + 10_000, q.nextDueAt())

        now += 9_999
        assertTrue(q.take().isEmpty())
        now += 1
        assertEquals(20, q.take().size)
        now += 10_000
        assertEquals(5, q.take().size)
    }

    @Test
    fun theChannelOnScreenAndTheNotifyingOnesGoFirst() {
        val q = queue { if (it == "c30") 0 else if (it == "c25") 1 else 2 }
        q.restart(channels(30))
        val first = q.take()
        assertEquals(listOf("c30", "c25"), first.take(2))
        assertEquals("the rest keep their order", (1..18).map { "c$it" }, first.drop(2))
    }

    @Test
    fun aChannelAddedLaterWaitsInTheSameLine() {
        val q = queue()
        q.restart(channels(20))
        q.take()
        q.add("late")
        assertTrue(q.take().isEmpty())
        now += 10_000
        assertEquals(listOf("late"), q.take())
    }

    @Test
    fun joinsSentOnTheOldConnectionStillCount() {
        val q = queue()
        q.restart(channels(15))
        q.take()
        now += 2_000
        q.restart(channels(15))
        assertEquals("only five are left in this window", 5, q.take().size)
    }

    @Test
    fun aChannelWithoutAnAnswerIsAskedForAgainLaterAndLaterStill() {
        val q = queue()
        q.restart(listOf("quiet", "fine"))
        q.take()
        q.answered("fine")
        assertEquals(now + 30_000, q.nextDueAt())

        now += 29_999
        assertTrue(q.take().isEmpty())
        now += 1
        assertEquals(listOf("quiet"), q.take())
        assertEquals("the second wait is twice as long", now + 60_000, q.nextDueAt())
        now += 60_000
        assertEquals(listOf("quiet"), q.take())
        q.answered("quiet")
        assertNull("nothing is left to do", q.nextDueAt())
    }

    @Test
    fun aRemovedChannelIsNeitherJoinedNorAskedForAgain() {
        val q = queue()
        q.restart(listOf("a", "b"))
        q.take()
        q.remove("a")
        q.answered("b")
        assertNull(q.nextDueAt())
        q.add("c")
        q.remove("c")
        assertTrue(q.take().isEmpty())
    }

    @Test
    fun addingAChannelThatIsAlreadyUnderWaySendsNothingNew() {
        val q = queue()
        q.restart(listOf("a"))
        q.take()
        q.add("a")
        assertTrue(q.take().isEmpty())
    }
}
