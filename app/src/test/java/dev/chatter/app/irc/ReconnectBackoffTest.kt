package dev.chatter.app.irc

import org.junit.Assert.assertEquals
import org.junit.Test

/** Reconnect delays on validated and unvalidated networks. */
class ReconnectBackoffTest {
    private fun waits(validated: Boolean, tries: Int) = (0 until tries).map { IrcConnection.backoffMs(it, validated) / 1000 }

    @Test
    fun aDroppedConnectionOnAWorkingNetworkIsRetriedQuicklyAtFirst() {
        assertEquals(listOf(1L, 2, 4, 8, 16, 30, 30, 30), waits(validated = true, tries = 8))
    }

    @Test
    fun afterFiveMinutesOfFailingTheWaitGrowsToFiveMinutes() {
        val waits = waits(validated = true, tries = 20)
        assertEquals(30L, waits[13])
        assertEquals(listOf(60L, 120, 240, 300, 300, 300), waits.drop(14))
    }

    @Test
    fun aNetworkWithoutInternetIsWaitedOnPatientlyFromTheStart() {
        assertEquals(listOf(1L, 2, 4, 8, 16, 32, 64, 128, 256, 300, 300), waits(validated = false, tries = 11))
    }

    @Test
    fun theWaitNeverGoesPastFiveMinutes() {
        assertEquals(300_000L, IrcConnection.backoffMs(1_000, validated = true))
        assertEquals(300_000L, IrcConnection.backoffMs(1_000, validated = false))
    }
}
