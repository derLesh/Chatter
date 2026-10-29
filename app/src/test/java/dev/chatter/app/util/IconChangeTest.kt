package dev.chatter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class IconChangeTest {
    private val halloween = LocalDate.of(2026, 10, 29)
    private val november = LocalDate.of(2026, 11, 2)

    @Test
    fun aSeasonSetsItsIconOnce() {
        assertEquals(
            IconChange(AppIcon.Halloween, "Halloween 2026-10-17"),
            iconChangeOn(halloween, AppIcon.Dark, AppIcon.Dark, auto = true, lastAutoWindow = null),
        )
        // Picked another one since: it stays.
        assertNull(iconChangeOn(halloween, AppIcon.Blue, AppIcon.Blue, auto = true, lastAutoWindow = "Halloween 2026-10-17"))
    }

    @Test
    fun theSameSeasonNextYearSetsItAgain() {
        assertEquals(
            IconChange(AppIcon.Halloween, "Halloween 2027-10-17"),
            iconChangeOn(LocalDate.of(2027, 10, 20), AppIcon.Dark, AppIcon.Dark, auto = true, lastAutoWindow = "Halloween 2026-10-17"),
        )
    }

    @Test
    fun nothingIsSetByItselfWhileTheSwitchIsOff() {
        assertNull(iconChangeOn(halloween, AppIcon.Dark, AppIcon.Dark, auto = false, lastAutoWindow = null))
    }

    @Test
    fun aSeasonalIconGivesWayAfterItsDays() {
        assertEquals(IconChange(AppIcon.Purple), iconChangeOn(november, AppIcon.Halloween, AppIcon.Purple, auto = true, lastAutoWindow = "Halloween 2026-10-17"))
        // Also when it was picked by hand.
        assertEquals(IconChange(AppIcon.Purple), iconChangeOn(november, AppIcon.Halloween, AppIcon.Purple, auto = false, lastAutoWindow = null))
    }

    @Test
    fun outsideEverySeasonNothingChanges() {
        assertNull(iconChangeOn(november, AppIcon.Dark, AppIcon.Dark, auto = true, lastAutoWindow = null))
    }

    @Test
    fun newYearsWindowIsNamedByTheYearItStarts() {
        assertEquals(
            IconChange(AppIcon.NewYear, "NewYear 2026-12-27"),
            iconChangeOn(LocalDate.of(2027, 1, 2), AppIcon.Christmas, AppIcon.Dark, auto = true, lastAutoWindow = "Christmas 2026-12-01"),
        )
    }
}
