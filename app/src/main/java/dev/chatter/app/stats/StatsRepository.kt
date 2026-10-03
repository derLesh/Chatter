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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
 * What the user has done in chat, counted and kept on this device. Days are stored as dates so they
 * can still be broken down later.
 */
@Serializable
data class Stats(
    /** Chat messages the user sent. */
    val sent: Long = 0,
    /** Messages that reached a chat, from anyone. */
    val received: Long = 0,
    /** Of those, the ones addressed to the user. */
    val mentions: Long = 0,
    /** Messages sent, per channel login. */
    val sentPerChannel: Map<String, Long> = emptyMap(),
    /** Days on which something was sent, as `2026-09-20`. */
    val activeDays: Set<String> = emptySet(),
    /** When counting started. */
    val since: Long = 0,
    /**
     * Messages received while the app was not on screen, per day and channel, to show what staying
     * joined costs. Last [KEEP_DAYS] days only.
     */
    val backgroundReceived: Map<String, Map<String, Long>> = emptyMap(),
    /** Data moved per day, on screen and in the background. Last [KEEP_DAYS] days only. */
    val traffic: Map<String, Traffic> = emptyMap(),
) {
    /** Channels by messages sent, most first. */
    val busiestChannels: List<Pair<String, Long>>
        get() = sentPerChannel.entries.sortedByDescending { it.value }.map { it.key to it.value }

    /** Background messages per channel over the last [days] days up to [today], most first. */
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

    /**
     * Consecutive days up to [today] with something sent; 0 if neither today nor yesterday has any.
     */
    fun streak(today: LocalDate = LocalDate.now()): Int {
        // Today only counts once it is over, so a streak may end yesterday.
        var day = if (today.toString() in activeDays) today else today.minusDays(1)
        var days = 0
        while (day.toString() in activeDays) {
            days++
            day = day.minusDays(1)
        }
        return days
    }

    companion object {
        /** The stats page shows a week at most. */
        const val KEEP_DAYS = 7

        /**
         * Channels in [counts] far above the rest: several times the median of the others and at
         * least [minimum], so a quiet week does not single one out for a few dozen lines.
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

/** Bytes received and sent while on screen and while in the background. */
@Serializable
data class Traffic(val open: Long = 0, val background: Long = 0) {
    operator fun plus(other: Traffic) = Traffic(open + other.open, background + other.background)
}

/**
 * Keeps [Stats] up to date while the chat runs. Counting only touches memory; the stats are written
 * on a slow timer that does nothing when nothing was counted.
 */
class StatsRepository(private val store: DataStore<Preferences>, private val scope: CoroutineScope) : ChatStats {
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats

    /**
     * Counted but not yet folded into [stats]. Plain counters instead of a new [Stats] per message,
     * since this runs for every message in every channel; [fold] merges them.
     */
    private val receivedDelta = AtomicLong()
    private val mentionDelta = AtomicLong()

    /** The same for background messages, one counter per channel. */
    private val backgroundDelta = ConcurrentHashMap<String, AtomicLong>()

    /** Set by [start]; on screen until then. */
    private var inFront: StateFlow<Boolean> = MutableStateFlow(true)

    /**
     * The traffic counter at the last reading (-1 before the first), and whether the app was on
     * screen since. Only [fold] touches them.
     */
    private var lastBytes = -1L
    private var bytesInFront = true

    /**
     * Signalled by every count, so the save loop sleeps while nothing happens instead of waking
     * every 30 seconds.
     */
    private val counted = Channel<Unit>(Channel.CONFLATED)

    /** What is on disk, to skip saves that change nothing. Null until loaded. */
    private var saved: Stats? = null
    private val saving = Mutex()

    /**
     * Starts loading and saving. On screen the stats are saved every 30 seconds; in the background
     * every ten minutes, since a busy channel all night would otherwise rewrite the file
     * constantly. Leaving the screen saves at once.
     */
    fun start(inFront: StateFlow<Boolean>) {
        this.inFront = inFront
        // First traffic reading, the baseline for all later ones.
        fold()
        scope.launch {
            saving.withLock {
                val loaded = load()
                _stats.value = loaded
                saved = loaded
            }
            // Fold on every change, so traffic is attributed to the side it happened on.
            launch {
                inFront.drop(1).collect { flush() }
            }
            while (isActive) {
                // Wait for a count, then let more pile up.
                counted.receive()
                delay(if (inFront.value) SAVE_INTERVAL_MS else BACKGROUND_SAVE_INTERVAL_MS)
                flush()
            }
        }
    }

    /**
     * Saves what was counted, for moments the process may die: leaving the screen, memory pressure,
     * the service stopping.
     */
    fun saveNow() {
        scope.launch { flush() }
    }

    private suspend fun flush() = saving.withLock {
        // Not loaded yet; saving would overwrite the stored stats.
        val before = saved ?: return@withLock
        fold()
        val current = _stats.value
        if (current != before) {
            save(current)
            saved = current
        }
    }

    /** Counts a sent message and marks today as active. */
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

    /** Counts a live message; history loaded on join does not count. */
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
     * Folds the pending counts and traffic into [stats]. Synchronized: screens and saving both call
     * it, and the traffic reading must be taken and replaced atomically.
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
     * Everything the app received and sent since the last reading, on the side it was on. The
     * counter only goes down when the phone restarts.
     */
    private fun readTraffic(): Traffic {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        val spentInFront = bytesInFront
        bytesInFront = inFront.value
        // Negative on phones that do not count per app.
        if (rx < 0 || tx < 0) return Traffic()
        val now = rx + tx
        val before = lastBytes
        lastBytes = now
        if (before < 0) return Traffic()
        val moved = if (now >= before) now - before else now
        return if (spentInFront) Traffic(open = moved) else Traffic(background = moved)
    }

    /** Stats for a screen, updated while it is collecting. */
    fun live(intervalMs: Long = LIVE_INTERVAL_MS): Flow<Stats> = flow {
        while (true) {
            fold()
            emit(_stats.value)
            delay(intervalMs)
        }
    }

    /** Discards everything and starts counting from now. */
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

    /** The stored stats, or new ones written right away, so "counting since" is the first start. */
    private suspend fun load(): Stats {
        decode(store.data.first()[STATS])?.let { return it }
        return Stats(since = System.currentTimeMillis()).also { save(it) }
    }

    private suspend fun save(stats: Stats) {
        store.edit { it[STATS] = AppJson.encodeToString(stats) }
    }

    private companion object {
        val STATS = stringPreferencesKey("stats")
        /** How much counting a process death may lose while on screen. */
        const val SAVE_INTERVAL_MS = 30_000L

        /** The same in the background, where every write wakes the disk. */
        const val BACKGROUND_SAVE_INTERVAL_MS = 10 * 60_000L

        /** Refresh rate of a screen showing the stats. */
        const val LIVE_INTERVAL_MS = 250L


        fun decode(raw: String?): Stats? =
            raw?.let { runCatching { AppJson.decodeFromString<Stats>(it) }.getOrNull() }
    }
}
