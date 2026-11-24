package me.lesh.chatter.stats

import me.lesh.chatter.net.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class YearStatsTest {
    private val emotesKept = 64

    private val monday = LocalDate.of(2026, 3, 2)

    private fun emote(name: String) = UsedEmote(name, "https://example.com/$name")

    @Test
    fun `a sent message counts towards its day, hour, channel and emotes`() {
        val stats = YearStats().withSent("xqc", monday, 21, listOf(emote("KEKW"), emote("Pog")))
        assertEquals(1, stats.sent)
        assertEquals(1, stats.withEmotes)
        assertEquals(mapOf("xqc" to 1L), stats.sentPerChannel)
        assertEquals(mapOf("2026-03-02" to 1L), stats.sentPerDay)
        assertEquals(1L, stats.sentPerHour[21])
        assertEquals(24, stats.sentPerHour.size)
        assertEquals(setOf("KEKW", "Pog"), stats.emotes.keys)
    }

    @Test
    fun `a message without emotes is not counted as one with emotes`() {
        assertEquals(0, YearStats().withSent("xqc", monday, 9, emptyList()).withEmotes)
    }

    @Test
    fun `only the kept number of emotes stays, and a new one takes the least used place`() {
        var stats = YearStats()
        repeat(3) { stats = stats.withEmote(emote("KEKW"), kept = 2) }
        stats = stats.withEmote(emote("Pog"), kept = 2)
        stats = stats.withEmote(emote("LUL"), kept = 2)
        assertEquals(setOf("KEKW", "LUL"), stats.emotes.keys)
        // LUL inherits Pog's single use.
        assertEquals(EmoteCount(2, "https://example.com/LUL", overcount = 1), stats.emotes["LUL"])
        assertEquals(listOf("KEKW", "LUL"), stats.topEmotes(5).map { it.first })
    }

    @Test
    fun `an emote that only inherited its count does not become the emote of the year`() {
        var stats = YearStats()
        repeat(emotesKept + 1) { i -> stats = stats.withEmote(emote("e$i"), kept = emotesKept) }
        val (name, top) = stats.topEmotes(1).single()
        assertEquals(1L, top.atLeast)
        assertTrue(name != "e$emotesKept")
    }

    @Test
    fun `an emote used often enough always makes it into the top`() {
        var stats = YearStats()
        // Many emotes used once, and one used every fourth message.
        repeat(400) { i ->
            stats = stats.withEmote(emote("rare$i"), kept = 16)
            if (i % 4 == 0) stats = stats.withEmote(emote("OMEGALUL"), kept = 16)
        }
        assertEquals("OMEGALUL", stats.topEmotes(1).single().first)
        assertEquals(16, stats.emotes.size)
    }

    @Test
    fun `the chatters a message speaks to are counted, at most the kept number`() {
        var stats = YearStats().withSent("xqc", monday, 20, emptyList(), listOf(TalkedTo("forsen", "Forsen")))
        stats = stats.withSent("xqc", monday, 21, emptyList(), listOf(TalkedTo("forsen", "FORSEN"), TalkedTo("lirik", "LIRIK")))
        assertEquals(listOf("forsen" to PersonCount(2, "FORSEN"), "lirik" to PersonCount(1, "LIRIK")), stats.topTalkedTo(5))
        stats = stats.withTalkedTo(TalkedTo("sodapoppin", "sodapoppin"), kept = 2)
        assertEquals(setOf("forsen", "sodapoppin"), stats.talkedTo.keys)
    }

    @Test
    fun `the chatters who mention the user are counted apart from those the user talks to`() {
        var stats = YearStats().withMentionedBy(TalkedTo("lirik", "LIRIK"))
        repeat(2) { stats = stats.withMentionedBy(TalkedTo("forsen", "forsen")) }
        assertEquals(listOf("forsen" to PersonCount(2, "forsen"), "lirik" to PersonCount(1, "LIRIK")), stats.topMentionedBy(5))
        assertTrue(stats.talkedTo.isEmpty())
    }

    @Test
    fun `the longest streak is the longest run of days in a row`() {
        val days = listOf("2026-01-01", "2026-01-02", "2026-02-10", "2026-02-11", "2026-02-12", "2026-03-01")
        val stats = YearStats(sentPerDay = days.associateWith { 1L })
        val streak = stats.longestStreak()!!
        assertEquals(LocalDate.of(2026, 2, 10), streak.from)
        assertEquals(3, streak.days)
    }

    @Test
    fun `a streak runs across the end of a month`() {
        val stats = YearStats(sentPerDay = listOf("2026-01-31", "2026-02-01").associateWith { 0L })
        assertEquals(2, stats.longestStreak()!!.days)
    }

    @Test
    fun `no streak without active days`() {
        assertNull(YearStats().longestStreak())
    }

    @Test
    fun `the busiest day ignores days whose count is unknown`() {
        val stats = YearStats(sentPerDay = mapOf("2026-01-01" to 0L, "2026-05-04" to 40L, "2026-06-01" to 12L))
        assertEquals(LocalDate.of(2026, 5, 4) to 40L, stats.busiestDay())
        assertNull(YearStats(sentPerDay = mapOf("2026-01-01" to 0L)).busiestDay())
    }

    @Test
    fun `messages add up per month`() {
        val stats = YearStats(sentPerDay = mapOf("2026-01-05" to 3L, "2026-01-20" to 4L, "2026-12-31" to 1L))
        val months = stats.sentPerMonth()
        assertEquals(7L, months[0])
        assertEquals(1L, months[11])
        assertEquals(8L, months.sum())
    }

    @Test
    fun `the favourite weekday needs enough messages`() {
        assertNull(YearStats(sentPerDay = mapOf("2026-03-02" to 5L)).favoriteWeekday())
        val stats = YearStats(sentPerDay = mapOf("2026-03-02" to 10L, "2026-03-07" to 25L))
        assertEquals(DayOfWeek.SATURDAY, stats.favoriteWeekday())
    }

    @Test
    fun `most messages late at night make a night owl`() {
        val hours = MutableList(24) { 1L }.apply { this[23] = 20; this[1] = 20 }
        assertEquals(ChatType.NightOwl, YearStats(sent = 62, sentPerHour = hours).chatType())
    }

    @Test
    fun `most messages in the morning make an early bird`() {
        val hours = MutableList(24) { 1L }.apply { this[7] = 30 }
        assertEquals(ChatType.EarlyBird, YearStats(sent = 53, sentPerHour = hours).chatType())
    }

    @Test
    fun `one channel taking most messages makes a regular`() {
        val stats = YearStats(sent = 100, sentPerChannel = mapOf("forsen" to 80, "xqc" to 20))
        assertEquals(ChatType.Regular, stats.chatType())
    }

    @Test
    fun `emotes in most messages make an emote artist`() {
        assertEquals(ChatType.EmoteArtist, YearStats(sent = 100, withEmotes = 70).chatType())
    }

    @Test
    fun `many channels make an explorer, and a little of everything an all-rounder`() {
        val many = (1..20).associate { "channel$it" to 5L }
        assertEquals(ChatType.Explorer, YearStats(sent = 100, sentPerChannel = many).chatType())
        assertEquals(ChatType.AllRounder, YearStats(sent = 5).chatType())
    }

    @Test
    fun `the current year is filled in from the totals when counting began this year`() {
        val today = LocalDate.of(2026, 11, 24)
        val since = today.minusDays(20).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val stats = Stats(
            sent = 50, mentions = 3, received = 900, sentPerChannel = mapOf("xqc" to 50),
            activeDays = setOf("2025-12-30", "2026-11-10"), since = since,
        ).withYearSeeded(today)
        val year = stats.year(2026)
        assertEquals(50, year.sent)
        assertEquals(3, year.mentions)
        assertEquals(mapOf("xqc" to 50L), year.sentPerChannel)
        assertEquals(mapOf("2026-11-10" to 0L), year.sentPerDay)
        assertEquals(LocalDate.of(2026, 11, 4), year.firstCountedDay(2026))
    }

    @Test
    fun `only active days carry over when counting began in an earlier year`() {
        val today = LocalDate.of(2027, 1, 2)
        val since = LocalDate.of(2026, 5, 1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val stats = Stats(sent = 50, activeDays = setOf("2026-12-30", "2027-01-01"), since = since).withYearSeeded(today)
        assertEquals(YearStats(sentPerDay = mapOf("2027-01-01" to 0L), countedFrom = "2027-01-02"), stats.year(2027))
    }

    @Test
    fun `a year is looked back on from December, past years at any time`() {
        val stats = Stats(years = mapOf(2025 to YearStats(sent = 500), 2026 to YearStats(sent = 300), 2024 to YearStats(sent = 2)))
        assertEquals(listOf(2025), stats.recapYears(LocalDate.of(2026, 11, 30)))
        assertEquals(listOf(2026, 2025), stats.recapYears(LocalDate.of(2026, 12, 1)))
    }

    @Test
    fun `the look back is shown off from December 1 to January 10`() {
        val stats = Stats(years = mapOf(2025 to YearStats(sent = 500), 2026 to YearStats(sent = 300)))
        assertNull(stats.featuredRecap(LocalDate.of(2026, 11, 30)))
        assertEquals(2026, stats.featuredRecap(LocalDate.of(2026, 12, 1)))
        assertEquals(2026, stats.featuredRecap(LocalDate.of(2027, 1, 10)))
        assertNull(stats.featuredRecap(LocalDate.of(2027, 1, 11)))
        assertNull(Stats(years = mapOf(2026 to YearStats(sent = 3))).featuredRecap(LocalDate.of(2026, 12, 5)))
    }

    @Test
    fun `year stats survive being stored`() {
        val stats = Stats(years = mapOf(2026 to YearStats().withSent("xqc", monday, 3, listOf(emote("KEKW"))).withMentionedBy(TalkedTo("lirik", "LIRIK"))))
        val read = AppJson.decodeFromString<Stats>(AppJson.encodeToString(stats))
        assertEquals(stats, read)
        assertTrue(AppJson.decodeFromString<Stats>("{\"sent\":3}").years.isEmpty())
    }
}
