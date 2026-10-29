package dev.chatter.app.util

import java.time.LocalDate

/** The days of the year a seasonal app icon can be picked on. */
enum class IconSeason {
    Valentine, Easter, Halloween, Christmas, NewYear;

    /** The days that start in [year], both ends included. New Year's runs into the next year. */
    fun window(year: Int): ClosedRange<LocalDate> = when (this) {
        Valentine -> LocalDate.of(year, 2, 7)..LocalDate.of(year, 2, 14)
        // Palm Sunday to Easter Monday.
        Easter -> easterSunday(year).let { it.minusDays(7)..it.plusDays(1) }
        Halloween -> LocalDate.of(year, 10, 17)..LocalDate.of(year, 10, 31)
        Christmas -> LocalDate.of(year, 12, 1)..LocalDate.of(year, 12, 26)
        NewYear -> LocalDate.of(year, 12, 27)..LocalDate.of(year + 1, 1, 6)
    }

    fun isOn(date: LocalDate): Boolean = date in window(date.year) || date in window(date.year - 1)

    /** The first day of the window [date] is in, or of the next one. */
    fun nextStart(date: LocalDate): LocalDate =
        (date.year - 1..date.year + 1).map(::window).first { date <= it.endInclusive }.start

    companion object {
        /** Easter Sunday in the Gregorian calendar (the anonymous Gregorian algorithm). */
        fun easterSunday(year: Int): LocalDate {
            val a = year % 19
            val b = year / 100
            val c = year % 100
            val d = b / 4
            val e = b % 4
            val f = (b + 8) / 25
            val g = (b - f + 1) / 3
            val h = (19 * a + b - d - g + 15) % 30
            val i = c / 4
            val k = c % 4
            val l = (32 + 2 * e + 2 * i - h - k) % 7
            val m = (a + 11 * h + 22 * l) / 451
            val month = (h + l - 7 * m + 114) / 31
            val day = (h + l - 7 * m + 114) % 31 + 1
            return LocalDate.of(year, month, day)
        }
    }
}
