package dev.chatter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class IconSeasonTest {
    @Test
    fun easterSundayMatchesTheCalendar() {
        assertEquals(LocalDate.of(2024, 3, 31), IconSeason.easterSunday(2024))
        assertEquals(LocalDate.of(2025, 4, 20), IconSeason.easterSunday(2025))
        assertEquals(LocalDate.of(2026, 4, 5), IconSeason.easterSunday(2026))
        assertEquals(LocalDate.of(2027, 3, 28), IconSeason.easterSunday(2027))
        assertEquals(LocalDate.of(2038, 4, 25), IconSeason.easterSunday(2038))
    }

    @Test
    fun easterRunsFromPalmSundayToEasterMonday() {
        assertFalse(IconSeason.Easter.isOn(LocalDate.of(2026, 3, 28)))
        assertTrue(IconSeason.Easter.isOn(LocalDate.of(2026, 3, 29)))
        assertTrue(IconSeason.Easter.isOn(LocalDate.of(2026, 4, 6)))
        assertFalse(IconSeason.Easter.isOn(LocalDate.of(2026, 4, 7)))
    }

    @Test
    fun bothEndsOfAWindowCount() {
        assertTrue(IconSeason.Halloween.isOn(LocalDate.of(2026, 10, 17)))
        assertTrue(IconSeason.Halloween.isOn(LocalDate.of(2026, 10, 31)))
        assertFalse(IconSeason.Halloween.isOn(LocalDate.of(2026, 11, 1)))
        assertFalse(IconSeason.Christmas.isOn(LocalDate.of(2026, 11, 30)))
        assertTrue(IconSeason.Christmas.isOn(LocalDate.of(2026, 12, 26)))
    }

    @Test
    fun newYearRunsIntoTheNextYear() {
        assertTrue(IconSeason.NewYear.isOn(LocalDate.of(2026, 12, 27)))
        assertTrue(IconSeason.NewYear.isOn(LocalDate.of(2027, 1, 1)))
        assertTrue(IconSeason.NewYear.isOn(LocalDate.of(2027, 1, 6)))
        assertFalse(IconSeason.NewYear.isOn(LocalDate.of(2027, 1, 7)))
        assertFalse(IconSeason.NewYear.isOn(LocalDate.of(2026, 12, 26)))
    }

    @Test
    fun nextStartIsThisYearsOrNextYears() {
        val today = LocalDate.of(2026, 10, 29)
        assertEquals(LocalDate.of(2027, 2, 7), IconSeason.Valentine.nextStart(today))
        assertEquals(LocalDate.of(2027, 3, 21), IconSeason.Easter.nextStart(today))
        assertEquals(LocalDate.of(2026, 12, 1), IconSeason.Christmas.nextStart(today))
        assertEquals(LocalDate.of(2026, 12, 27), IconSeason.NewYear.nextStart(today))
        assertEquals(LocalDate.of(2027, 10, 17), IconSeason.Halloween.nextStart(LocalDate.of(2026, 11, 1)))
    }

    @Test
    fun nextStartInsideNewYearIsTheWindowItIsIn() {
        assertEquals(LocalDate.of(2026, 12, 27), IconSeason.NewYear.nextStart(LocalDate.of(2027, 1, 3)))
    }
}
