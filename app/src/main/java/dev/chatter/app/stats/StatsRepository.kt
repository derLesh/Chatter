package dev.chatter.app.stats

import android.net.TrafficStats
import android.os.Process
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chatter.app.chat.ChatStats
import dev.chatter.app.net.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * What the user has done in chat so far. Everything in here is counted on this device and stays
 * on it.
 *
 * The days are kept as dates rather than as a count, because a year is only worth looking back
 * on if it can still be broken down afterwards.
 */
@Serializable
data class Stats(
    /** Chat messages the user sent. */
    val sent: Long = 0,
    /** Messages that reached a chat, whoever wrote them. */
    val received: Long = 0,
    /** Of those, the ones addressed to the user. */
    val mentions: Long = 0,
    /** Messages sent, per channel login. */
    val sentPerChannel: Map<String, Long> = emptyMap(),
    /** The days something was sent, as `2026-09-20`. */
    val activeDays: Set<String> = emptySet(),
    /** When the counting started, so the numbers can be read against a stretch of time. */
    val since: Long = 0,
    /**
     * Messages that arrived while the app was not on screen, per day and channel login — what
     * staying joined overnight costs, channel by channel. Only the last [KEEP_DAYS] days.
     */
    val backgroundReceived: Map<String, Map<String, Long>> = emptyMap(),
    /** The data Chatter moved per day, split by whether it was on screen. Only the last [KEEP_DAYS] days. */
    val traffic: Map<String, Traffic> = emptyMap(),
) {
    /** Channels by how much was said in them, busiest first. */
    val busiestChannels: List<Pair<String, Long>>
        get() = sentPerChannel.entries.sortedByDescending { it.value }.map { it.key to it.value }

    /** Messages per channel that arrived in the background over the last [days] days up to [today], most first. */
    fun backgroundByChannel(days: Int, today: LocalDate = LocalDate.now()): List<Pair<String, Long>> {
        val from = today.minusDays(days - 1L).toString()
        val totals = HashMap<String, Long>()
        backgroundReceived.forEach { (day, channels) ->
            if (day >= from) channels.forEach { (login, n) -> totals[login] = (totals[login] ?: 0) + n }
        }
        return totals.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    /** The data moved over the last [days] days up to [today]. */
    fun trafficOver(days: Int, today: LocalDate = LocalDate.now()): Traffic {
        val from = today.minusDays(days - 1L).toString()
        return traffic.filterKeys { it >= from }.values.fold(Traffic()) { sum, t -> sum + t }
    }

    /** Days in a row up to [today] on which something was sent; 0 if today and yesterday are empty. */
    fun streak(today: LocalDate = LocalDate.now()): Int {
        // Today does not count against a streak until it is over, so it may start at yesterday.
        var day = if (today.toString() in activeDays) today else today.minusDays(1)
        var days = 0
        while (day.toString() in activeDays) {
            days++
            day = day.minusDays(1)
        }
        return days
    }

    companion object {
        /** How many days of the background figures are kept; the stats page shows a week at most. */
        const val KEEP_DAYS = 7

        /**
         * The channels of [counts] that bring in far more than the others: several times the
         * median of the rest, and a good number in absolute terms, so that a week in which every
         * channel was quiet does not single one out for a few dozen lines.
         */
        fun outliers(counts: List<Pair<String, Long>>, minimum: Long = OUTLIER_MINIMUM): Set<String> {
            if (counts.size < 2) return emptySet()
            return counts.filter { (login, n) ->
                val others = counts.filter { it.first != login }.map { it.second }.sorted()
                val median = others[others.size / 2].coerceAtLeast(1)
                n >= minimum && n >= median * OUTLIER_FACTOR
            }.map { it.first }.toSet()
        }

        private const val OUTLIER_FACTOR = 5
        private const val OUTLIER_MINIMUM = 1_000L
    }
}

/** Bytes Chatter received and sent while it was on screen, and while it was not. */
@Serializable
data class Traffic(val open: Long = 0, val background: Long = 0) {
    operator fun plus(other: Traffic) = Traffic(open + other.open, background + other.background)
}

/**
 * Keeps [Stats] up to date while the chat runs.
 *
 * Counting has to be free: a busy channel would otherwise buy a disk write for every message
 * nobody asked to have counted. So a count is a number in memory, and the whole thing goes to
 * disk on a slow heartbeat that does nothing at all when nothing happened.
 */
class StatsRepository(private val store: DataStore<Preferences>, private val scope: CoroutineScope) : ChatStats {
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats

    /**
     * Messages and mentions counted but not yet in [stats].
     *
     * Two numbers instead of a new [Stats] for every message that reaches any channel: this runs
     * all day, in every joined channel, whether or not anybody is looking, and nothing reads the
     * total until a screen shows it or it is written to disk. [fold] is where they meet.
     */
    private val receivedDelta = AtomicLong()
    private val mentionDelta = AtomicLong()

    /**
     * The same for messages that arrived while the app was in the background, per channel. One
     * counter per joined channel, made once; counting a message looks it up and adds one.
     */
    private val backgroundDelta = ConcurrentHashMap<String, AtomicLong>()

    /** Whether the app is on screen; see [start]. On screen until told otherwise, so nothing is miscounted. */
    private var inFront: StateFlow<Boolean> = MutableStateFlow(true)

    /**
     * The app's own traffic counter when it was last read, or -1 before the first reading, and
     * whether what it moved since then was moved on screen. Only [fold] touches them.
     */
    private var lastBytes = -1L
    private var bytesInFront = true

    /**
     * Counting something rings this; nothing else does. A heartbeat that wakes up every half
     * minute to find that nobody has written anything is a wakeup an idle phone should not have
     * to pay for, and the app spends most of its life exactly like that.
     */
    private val counted = Channel<Unit>(Channel.CONFLATED)

    /** What is on disk, so that a save with nothing new to say can be skipped. Null until loaded. */
    private var saved: Stats? = null
    private val saving = Mutex()

    /**
     * Starts loading and saving. [inFront] is whether the app is on screen: the stats page may be
     * open then, and the numbers are saved every half minute. In the background, with a busy
     * channel joined all night, that would be a rewrite of the whole file twice a minute for
     * numbers nobody reads, so they pile up for ten minutes at a time instead — and are saved the
     * moment the app leaves the screen, which is when the process starts being at risk.
     */
    fun start(inFront: StateFlow<Boolean>) {
        this.inFront = inFront
        // The first reading of the traffic counter, which everything after is measured from.
        fold()
        scope.launch {
            saving.withLock {
                val loaded = load()
                _stats.value = loaded
                saved = loaded
            }
            // Every change is also where the data used so far changes sides: it is read right
            // then, so that what was moved on screen is not put down to the background or back.
            launch {
                inFront.drop(1).collect { flush() }
            }
            while (isActive) {
                // Wait for something to count, then let the next while of it pile up.
                counted.receive()
                delay(if (inFront.value) SAVE_INTERVAL_MS else BACKGROUND_SAVE_INTERVAL_MS)
                flush()
            }
        }
    }

    /**
     * Writes what was counted so far, if anything was. For the moments the process may be about to
     * go — the app leaving the screen, Android asking for memory back, the service being stopped —
     * so that what is lost to a kill is at most the last few minutes.
     */
    fun saveNow() {
        scope.launch { flush() }
    }

    private suspend fun flush() = saving.withLock {
        // Not loaded yet: saving now would put fresh stats over the ones on disk.
        val before = saved ?: return@withLock
        fold()
        val current = _stats.value
        if (current != before) {
            save(current)
            saved = current
        }
    }

    /** Counts a message the user sent, and marks today as a day they were around. */
    override fun countSent(channel: String) {
        val today = LocalDate.now().toString()
        _stats.update {
            it.copy(
                sent = it.sent + 1,
                sentPerChannel = it.sentPerChannel + (channel to (it.sentPerChannel[channel] ?: 0) + 1),
                activeDays = if (today in it.activeDays) it.activeDays else it.activeDays + today,
            )
        }
        counted.trySend(Unit)
    }

    /** Counts a message that arrived live. History loaded on join is not new and does not count. */
    override fun countReceived(channel: String) {
        receivedDelta.incrementAndGet()
        if (!inFront.value) backgroundDelta.computeIfAbsent(channel) { AtomicLong() }.incrementAndGet()
        counted.trySend(Unit)
    }

    override fun countMention() {
        mentionDelta.incrementAndGet()
        counted.trySend(Unit)
    }

    /**
     * Adds what was counted since the last time into [stats], and the data used since then to
     * today. Synchronized because both a screen and the saving call it, and the traffic reading
     * must be taken and replaced in one go.
     */
    @Synchronized
    private fun fold() {
        val received = receivedDelta.getAndSet(0)
        val mentions = mentionDelta.getAndSet(0)
        val background = HashMap<String, Long>()
        backgroundDelta.forEach { (channel, n) -> n.getAndSet(0).takeIf { it > 0 }?.let { background[channel] = it } }
        val moved = readTraffic()
        if (received == 0L && mentions == 0L && background.isEmpty() && moved == Traffic()) return
        val today = LocalDate.now()
        val day = today.toString()
        val oldest = today.minusDays(Stats.KEEP_DAYS - 1L).toString()
        _stats.update { s ->
            val counts = if (background.isEmpty()) s.backgroundReceived else {
                val todays = s.backgroundReceived[day].orEmpty().toMutableMap()
                background.forEach { (channel, n) -> todays[channel] = (todays[channel] ?: 0) + n }
                s.backgroundReceived + (day to todays)
            }
            val traffic = if (moved == Traffic()) s.traffic else s.traffic + (day to (s.traffic[day] ?: Traffic()) + moved)
            s.copy(
                received = s.received + received,
                mentions = s.mentions + mentions,
                backgroundReceived = counts.filterKeys { it >= oldest },
                traffic = traffic.filterKeys { it >= oldest },
            )
        }
    }

    /**
     * What the app received and sent since the last reading — all of it, from the chat to the
     * emotes — on the side it was on then. The counter starts over when the phone does, which is
     * the one time it goes down.
     */
    private fun readTraffic(): Traffic {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        val spentInFront = bytesInFront
        bytesInFront = inFront.value
        // Not every phone counts per app; those say so with a negative number.
        if (rx < 0 || tx < 0) return Traffic()
        val now = rx + tx
        val before = lastBytes
        lastBytes = now
        if (before < 0) return Traffic()
        val moved = if (now >= before) now - before else now
        return if (spentInFront) Traffic(open = moved) else Traffic(background = moved)
    }

    /**
     * The stats for a screen that shows them: brought up to date as they are read, and only for
     * as long as somebody is reading.
     */
    fun live(intervalMs: Long = LIVE_INTERVAL_MS): Flow<Stats> = flow {
        while (true) {
            fold()
            emit(_stats.value)
            delay(intervalMs)
        }
    }

    /** Throws everything counted so far away and starts over from now. */
    suspend fun reset() {
        val fresh = Stats(since = System.currentTimeMillis())
        saving.withLock {
            receivedDelta.set(0)
            mentionDelta.set(0)
            backgroundDelta.clear()
            _stats.value = fresh
            save(fresh)
            saved = fresh
        }
    }

    /**
     * The stored stats, or fresh ones written straight back — so that "counting since" is the
     * first start rather than whichever start happened to be the one that counted something.
     */
    private suspend fun load(): Stats {
        decode(store.data.first()[STATS])?.let { return it }
        return Stats(since = System.currentTimeMillis()).also { save(it) }
    }

    private suspend fun save(stats: Stats) {
        store.edit { it[STATS] = AppJson.encodeToString(stats) }
    }

    private companion object {
        val STATS = stringPreferencesKey("stats")
        /** How much counting a sudden death of the process may cost while the app is on screen. */
        const val SAVE_INTERVAL_MS = 30_000L

        /** The same in the background, where nobody is looking and every write wakes the disk. */
        const val BACKGROUND_SAVE_INTERVAL_MS = 10 * 60_000L

        /** How often a screen showing the stats sees them move. */
        const val LIVE_INTERVAL_MS = 250L


        fun decode(raw: String?): Stats? =
            raw?.let { runCatching { AppJson.decodeFromString<Stats>(it) }.getOrNull() }
    }
}
