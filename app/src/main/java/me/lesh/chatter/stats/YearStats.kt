package me.lesh.chatter.stats

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * What the user did in chat in one calendar year, for the stats page and the look back at the year.
 * Days and hours are on the phone's clock.
 */
@Serializable
data class YearStats(
    val sent: Long = 0,
    /** Of [sent], the messages with at least one emote. */
    val withEmotes: Long = 0,
    val mentions: Long = 0,
    val received: Long = 0,
    val sentPerChannel: Map<String, Long> = emptyMap(),
    /**
     * Messages sent per day, as `2026-09-20`. 0 marks a day with messages from before they were
     * counted per day: it was active, but how busy is unknown.
     */
    val sentPerDay: Map<String, Long> = emptyMap(),
    /** Messages sent per hour of the day, 0 to 23; empty until the first one. */
    val sentPerHour: List<Long> = emptyList(),
    /** The most used emotes by name, at most [EMOTES_KEPT]; see [withEmote]. */
    val emotes: Map<String, EmoteCount> = emptyMap(),
    /**
     * The chatters the user @mentioned or answered most, by login, at most [PEOPLE_KEPT]; kept the
     * same way as [emotes].
     */
    val talkedTo: Map<String, PersonCount> = emptyMap(),
    /** The chatters who mentioned the user most, by login, at most [PEOPLE_KEPT]; kept like [emotes]. */
    val mentionedBy: Map<String, PersonCount> = emptyMap(),
    /** The day counting for this year began, as `2026-11-07`; null in years stored before it was kept. */
    val countedFrom: String? = null,
) {
    /**
     * Counts one sent message at [day] and [hour], with the emotes in it and the chatters it
     * speaks to, each once.
     */
    fun withSent(
        channel: String,
        day: LocalDate,
        hour: Int,
        emotes: Collection<UsedEmote>,
        people: Collection<TalkedTo> = emptyList(),
    ): YearStats {
        val key = day.toString()
        val hours = if (sentPerHour.size == 24) sentPerHour.toMutableList() else MutableList(24) { 0L }
        hours[hour] += 1
        return copy(
            sent = sent + 1,
            withEmotes = if (emotes.isEmpty()) withEmotes else withEmotes + 1,
            sentPerChannel = sentPerChannel + (channel to (sentPerChannel[channel] ?: 0) + 1),
            sentPerDay = sentPerDay + (key to (sentPerDay[key] ?: 0) + 1),
            sentPerHour = hours,
        ).let { counted -> emotes.fold(counted) { s, e -> s.withEmote(e) } }
            .let { counted -> people.fold(counted) { s, p -> s.withTalkedTo(p) } }
    }

    /**
     * Counts one use of [emote] while keeping only [EMOTES_KEPT] of them, by the Space-Saving
     * method: a new emote takes the place of the least used one and inherits its count, so an emote
     * used often enough always makes it in, and the top few are right even though most emotes
     * are never kept.
     */
    fun withEmote(emote: UsedEmote, kept: Int = EMOTES_KEPT): YearStats = copy(
        emotes = emotes.spaceSaving(emote.name, kept, { it.count }, { it.overcount }) { inherited, over -> EmoteCount(inherited + 1, emote.url, over) },
    )

    /** Counts one message to [person], keeping [kept] of them like [withEmote]. */
    fun withTalkedTo(person: TalkedTo, kept: Int = PEOPLE_KEPT): YearStats = copy(
        talkedTo = talkedTo.spaceSaving(person.login, kept, { it.count }, { it.overcount }) { inherited, over -> PersonCount(inherited + 1, person.name, over) },
    )

    /** Counts one mention by [person], keeping [kept] of them like [withEmote]. */
    fun withMentionedBy(person: TalkedTo, kept: Int = PEOPLE_KEPT): YearStats = copy(
        mentionedBy = mentionedBy.spaceSaving(person.login, kept, { it.count }, { it.overcount }) { inherited, over -> PersonCount(inherited + 1, person.name, over) },
    )

    /** Chatters by messages to them, most first by login, ranked like [topEmotes]. */
    fun topTalkedTo(n: Int): List<Pair<String, PersonCount>> = talkedTo.topPeople(n)

    /** Chatters by mentions of the user, most first by login, ranked like [topEmotes]. */
    fun topMentionedBy(n: Int): List<Pair<String, PersonCount>> = mentionedBy.topPeople(n)

    val activeDays: Int get() = sentPerDay.size

    /** The first day counted in [year], for rates such as mentions per week. */
    fun firstCountedDay(year: Int): LocalDate {
        val known = countedFrom?.let(::parseDay) ?: sentPerDay.keys.mapNotNull(::parseDay).minOrNull()
        return maxOf(LocalDate.of(year, 1, 1), known ?: LocalDate.of(year, 1, 1))
    }

    /**
     * Emotes by use, most first. Ranked by the uses they surely had, so an emote that came in late
     * and inherited a count does not pass one that was really used more often.
     */
    fun topEmotes(n: Int): List<Pair<String, EmoteCount>> =
        emotes.entries.sortedWith(compareByDescending<Map.Entry<String, EmoteCount>> { it.value.atLeast }.thenBy { it.value.overcount }.thenBy { it.key })
            .take(n).map { it.key to it.value }

    fun topChannels(n: Int): List<Pair<String, Long>> =
        sentPerChannel.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(n).map { it.key to it.value }

    /** The day with the most messages, or null while no day was counted. */
    fun busiestDay(): Pair<LocalDate, Long>? =
        sentPerDay.entries.filter { it.value > 0 }
            .maxWithOrNull(compareBy<Map.Entry<String, Long>> { it.value }.thenByDescending { it.key })
            ?.let { (day, n) -> parseDay(day)?.let { it to n } }

    /** The longest run of active days in a row, with its first and last day. */
    fun longestStreak(): Streak? {
        val days = sentPerDay.keys.mapNotNull(::parseDay).sorted()
        if (days.isEmpty()) return null
        var best = Streak(days[0], days[0])
        var start = days[0]
        for (i in 1 until days.size) {
            if (days[i] != days[i - 1].plusDays(1)) start = days[i]
            val run = Streak(start, days[i])
            if (run.days > best.days) best = run
        }
        return best
    }

    /** Messages per month, January first; days without a count add nothing. */
    fun sentPerMonth(): List<Long> {
        val months = LongArray(12)
        sentPerDay.forEach { (day, n) -> parseDay(day)?.let { months[it.monthValue - 1] += n } }
        return months.toList()
    }

    /** The weekday with the most messages, once at least [MIN_FOR_PATTERN] were counted per day. */
    fun favoriteWeekday(): DayOfWeek? {
        val weekdays = LongArray(7)
        var counted = 0L
        sentPerDay.forEach { (day, n) ->
            parseDay(day)?.let { weekdays[it.dayOfWeek.value - 1] += n }
            counted += n
        }
        if (counted < MIN_FOR_PATTERN) return null
        return DayOfWeek.of(weekdays.indices.maxBy { weekdays[it] } + 1)
    }

    /** The hour the user wrote most in, once at least [MIN_FOR_PATTERN] were counted per hour. */
    fun peakHour(): Int? = sentPerHour.takeIf { it.sum() >= MIN_FOR_PATTERN }?.let { hours -> hours.indices.maxBy { hours[it] } }

    /**
     * What kind of chatter the year shows, the way other looks back give their users a type. The
     * first that fits wins, so the time of day, which says most about a person, goes first.
     */
    fun chatType(): ChatType {
        val counted = sentPerHour.sum()
        if (counted >= MIN_FOR_PATTERN) {
            val night = (NIGHT_FROM..23).sumOf { sentPerHour[it] } + (0 until NIGHT_UNTIL).sumOf { sentPerHour[it] }
            if (night >= counted * NIGHT_SHARE) return ChatType.NightOwl
            val morning = (MORNING_FROM until MORNING_UNTIL).sumOf { sentPerHour[it] }
            if (morning >= counted * MORNING_SHARE) return ChatType.EarlyBird
        }
        if (sent >= MIN_FOR_PATTERN && withEmotes >= sent * EMOTE_SHARE) return ChatType.EmoteArtist
        val top = sentPerChannel.values.maxOrNull() ?: 0
        if (sent >= MIN_FOR_PATTERN && top >= sent * REGULAR_SHARE) return ChatType.Regular
        if (sentPerChannel.size >= EXPLORER_CHANNELS) return ChatType.Explorer
        return ChatType.AllRounder
    }

    companion object {
        /** Enough to keep the top ten right in a year of a few thousand different emotes. */
        const val EMOTES_KEPT = 64

        /** Enough for the top five of the people someone talks to. */
        const val PEOPLE_KEPT = 32

        /** Fewer messages than this say nothing about a pattern. */
        const val MIN_FOR_PATTERN = 30L

        private const val NIGHT_FROM = 22
        private const val NIGHT_UNTIL = 5
        private const val NIGHT_SHARE = 0.4
        private const val MORNING_FROM = 5
        private const val MORNING_UNTIL = 10
        private const val MORNING_SHARE = 0.35
        private const val EMOTE_SHARE = 0.5
        private const val REGULAR_SHARE = 0.6
        private const val EXPLORER_CHANNELS = 15

        fun parseDay(day: String): LocalDate? = try {
            LocalDate.parse(day)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

/**
 * Uses of one emote and the address of its picture. [overcount] is how much of [count] it may have
 * inherited from the emote it replaced; see [YearStats.withEmote].
 */
@Serializable
data class EmoteCount(val count: Long, val url: String, val overcount: Long = 0) {
    /** The uses it surely had, the number shown. */
    val atLeast: Long get() = count - overcount
}

/**
 * Adds one to [key], or makes room for it in a map of at most [kept] entries: [make] gets the count
 * the new entry starts from (0, or the least one's when it replaces that) and how much of it was
 * inherited. A known key goes through [make] too, from its own count, so it also takes the newest
 * name or picture.
 */
private fun <T> Map<String, T>.spaceSaving(
    key: String,
    kept: Int,
    count: (T) -> Long,
    overcount: (T) -> Long,
    make: (inherited: Long, overcount: Long) -> T,
): Map<String, T> {
    this[key]?.let { known -> return this + (key to make(count(known), overcount(known))) }
    if (size < kept) return this + (key to make(0, 0))
    val (leastKey, least) = minBy { count(it.value) }
    return this - leastKey + (key to make(count(least), count(least)))
}

private fun Map<String, PersonCount>.topPeople(n: Int): List<Pair<String, PersonCount>> =
    entries.sortedWith(compareByDescending<Map.Entry<String, PersonCount>> { it.value.atLeast }.thenBy { it.value.overcount }.thenBy { it.key })
        .take(n).map { it.key to it.value }

/** Messages to or from one chatter and the name they were last seen with. */
@Serializable
data class PersonCount(val count: Long, val name: String, val overcount: Long = 0) {
    /** The messages it surely had, the number shown. */
    val atLeast: Long get() = count - overcount
}

/** A chatter a sent message speaks to, by @mention or by answering them, or one who mentioned the user. */
data class TalkedTo(val login: String, val name: String)

/** An emote in a sent message, as the stats need it. */
data class UsedEmote(val name: String, val url: String)

data class Streak(val from: LocalDate, val to: LocalDate) {
    val days: Int get() = (to.toEpochDay() - from.toEpochDay() + 1).toInt()
}

enum class ChatType { NightOwl, EarlyBird, EmoteArtist, Regular, Explorer, AllRounder }
