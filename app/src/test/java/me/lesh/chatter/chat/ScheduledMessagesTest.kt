package me.lesh.chatter.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Planned messages against a clock the test moves. */
class ScheduledMessagesTest {
    private class FakeStore : ScheduledStore {
        val state = MutableStateFlow<List<ScheduledMessage>>(emptyList())
        override val messages = state

        override suspend fun update(change: (List<ScheduledMessage>) -> List<ScheduledMessage>) = state.update(change)
    }

    private val store = FakeStore()
    private val delivered = mutableListOf<String>()
    private var answer = SendResult.Ok
    private val alarms = mutableListOf<Long?>()

    private fun TestScope.scheduler() = ScheduledMessages(
        store, backgroundScope,
        deliver = { delivered += it.text; answer },
        alarm = { alarms += it },
        now = { testScheduler.currentTime },
    ).also { it.start() }

    @Test
    fun sendsAtItsTimeAndNotBefore() = runTest {
        val scheduled = scheduler()
        scheduled.add("me", "forsen", "hello", replyTo = null, sendAt = 60_000)
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(emptyList<String>(), delivered)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("hello"), delivered)
        assertEquals(emptyList<ScheduledMessage>(), store.state.value)
    }

    @Test
    fun sendsInTheOrderPlanned() = runTest {
        val scheduled = scheduler()
        scheduled.add("me", "forsen", "second", replyTo = null, sendAt = 2_000)
        scheduled.add("me", "forsen", "first", replyTo = null, sendAt = 1_000)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf("first", "second"), delivered)
    }

    @Test
    fun oneThatCannotGoOutComesBackFailed() = runTest {
        answer = SendResult.NotConnected
        val scheduled = scheduler()
        val failures = mutableListOf<ScheduledMessage>()
        backgroundScope.launch { scheduled.failures.collect { failures += it } }
        scheduled.add("me", "forsen", "hello", replyTo = null, sendAt = 1_000)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(true), store.state.value.map { it.failed })
        assertEquals(listOf("hello"), failures.map { it.text })
    }

    @Test
    fun oneFoundLongAfterItsTimeIsNotSent() = runTest {
        advanceTimeBy(ScheduledMessages.MISSED_AFTER_MS + 60_000)
        val scheduled = scheduler()
        scheduled.add("me", "forsen", "good morning", replyTo = null, sendAt = 0)
        runCurrent()
        assertEquals(emptyList<String>(), delivered)
        assertEquals(listOf(true), store.state.value.map { it.failed })
    }

    @Test
    fun oneTwitchRefusesComesBackFailed() = runTest {
        val scheduled = scheduler()
        scheduled.add("me", "forsen", "hello", replyTo = null, sendAt = 1_000)
        advanceTimeBy(1_500)
        runCurrent()
        scheduled.onRefused("forsen")
        runCurrent()
        assertEquals(listOf("hello"), store.state.value.filter { it.failed }.map { it.text })
    }

    @Test
    fun aLateRefusalIsAboutSomethingElse() = runTest {
        val scheduled = scheduler()
        scheduled.add("me", "forsen", "hello", replyTo = null, sendAt = 1_000)
        advanceTimeBy(1_500)
        runCurrent()
        advanceTimeBy(30_000)
        scheduled.onRefused("forsen")
        runCurrent()
        assertEquals(emptyList<ScheduledMessage>(), store.state.value)
    }

    @Test
    fun aTakenMessageIsNotSent() = runTest {
        val scheduled = scheduler()
        scheduled.add("me", "forsen", "hello", replyTo = null, sendAt = 1_000)
        runCurrent()
        val id = store.state.value.single().id
        assertEquals("hello", scheduled.take(id)?.text)
        assertNull(scheduled.take(id))
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(emptyList<String>(), delivered)
    }

    @Test
    fun theAlarmFollowsTheNextMessage() = runTest {
        val scheduled = scheduler()
        scheduled.add("me", "forsen", "hello", replyTo = null, sendAt = 5_000)
        runCurrent()
        assertEquals(5_000L, alarms.last())
        advanceTimeBy(6_000)
        runCurrent()
        assertNull(alarms.last())
    }

    @Test
    fun aFarAlarmIsSetEarlySoAMovedOneStillComesInTime() {
        val now = 1_000_000L
        val wake = ScheduledMessages.wakeAt(now + 70_000, now)
        assertEquals(now + 40_000, wake)
        // Android moves an alarm back by at most three quarters of the time until it.
        assertTrue(wake + (wake - now) * 3 / 4 <= now + 70_000)
        assertEquals(now + 8_000, ScheduledMessages.wakeAt(now + 8_000, now))
    }

    @Test
    fun aTimeOfDayIsTheNextOneOnTheClock() {
        val zone = ZoneId.of("Europe/Berlin")
        val now = ZonedDateTime.of(2026, 11, 6, 21, 30, 15, 0, zone).toInstant().toEpochMilli()
        assertEquals(
            ZonedDateTime.of(2026, 11, 6, 22, 0, 0, 0, zone).toInstant().toEpochMilli(),
            ScheduledMessages.nextAt(22, 0, now, zone),
        )
        assertEquals(
            ZonedDateTime.of(2026, 11, 7, 7, 0, 0, 0, zone).toInstant().toEpochMilli(),
            ScheduledMessages.nextAt(7, 0, now, zone),
        )
        // The minute that is running now has begun already.
        assertEquals(
            ZonedDateTime.of(2026, 11, 7, 21, 30, 0, 0, zone).toInstant().toEpochMilli(),
            ScheduledMessages.nextAt(21, 30, now, zone),
        )
    }
}
