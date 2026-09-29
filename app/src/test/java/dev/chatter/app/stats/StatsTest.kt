package dev.chatter.app.stats

import dev.chatter.app.net.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The background figures on the stats page, worked out from what [StatsRepository] keeps. */
class StatsTest {
    private val today = LocalDate.of(2026, 9, 29)

    private val stats = Stats(
        backgroundReceived = mapOf(
            "2026-09-29" to mapOf("busy" to 500L, "quiet" to 5L),
            "2026-09-28" to mapOf("busy" to 700L, "other" to 20L),
            // Older than a week: kept by nobody, but ignored if it were.
            "2026-09-01" to mapOf("quiet" to 9_000L),
        ),
        traffic = mapOf(
            "2026-09-29" to Traffic(open = 100, background = 10),
            "2026-09-25" to Traffic(open = 1, background = 2),
        ),
    )

    @Test
    fun backgroundMessagesAddUpPerChannelOverTheDaysAsked() {
        assertEquals(listOf("busy" to 500L, "quiet" to 5L), stats.backgroundByChannel(1, today))
        assertEquals(listOf("busy" to 1_200L, "other" to 20L, "quiet" to 5L), stats.backgroundByChannel(7, today))
    }

    @Test
    fun trafficAddsUpPerSide() {
        assertEquals(Traffic(open = 100, background = 10), stats.trafficOver(1, today))
        assertEquals(Traffic(open = 101, background = 12), stats.trafficOver(7, today))
    }

    @Test
    fun aChannelBringingInFarMoreThanTheOthersIsPointedOut() {
        val week = listOf("busy" to 50_000L, "a" to 800L, "b" to 1_200L)
        assertEquals(setOf("busy"), Stats.outliers(week))
    }

    @Test
    fun twoChannelsAreComparedWithEachOther() {
        assertEquals(setOf("busy"), Stats.outliers(listOf("busy" to 10_000L, "quiet" to 100L)))
    }

    @Test
    fun nothingIsPointedOutWhenEveryChannelIsAboutAsBusy() {
        assertTrue(Stats.outliers(listOf("a" to 10_000L, "b" to 8_000L, "c" to 12_000L)).isEmpty())
    }

    @Test
    fun aQuietWeekSinglesNobodyOut() {
        assertTrue(Stats.outliers(listOf("a" to 300L, "b" to 2L, "c" to 1L)).isEmpty())
    }

    @Test
    fun oneChannelHasNothingToBeComparedWith() {
        assertTrue(Stats.outliers(listOf("only" to 100_000L)).isEmpty())
    }

    @Test
    fun statsSavedBeforeTheBackgroundFiguresStillLoad() {
        val old = """{"sent":3,"received":40,"mentions":1,"sentPerChannel":{"a":3},"activeDays":["2026-09-20"],"since":5}"""
        val decoded = AppJson.decodeFromString<Stats>(old)
        assertEquals(40L, decoded.received)
        assertTrue(decoded.backgroundReceived.isEmpty())
        assertTrue(decoded.traffic.isEmpty())
    }
}
