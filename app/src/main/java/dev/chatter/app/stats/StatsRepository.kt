package dev.chatter.app.stats

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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.time.LocalDate
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
) {
    /** Channels by how much was said in them, busiest first. */
    val busiestChannels: List<Pair<String, Long>>
        get() = sentPerChannel.entries.sortedByDescending { it.value }.map { it.key to it.value }

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
        scope.launch {
            saving.withLock {
                val loaded = load()
                _stats.value = loaded
                saved = loaded
            }
            launch {
                inFront.drop(1).filter { !it }.collect { flush() }
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
    override fun countReceived() {
        receivedDelta.incrementAndGet()
        counted.trySend(Unit)
    }

    override fun countMention() {
        mentionDelta.incrementAndGet()
        counted.trySend(Unit)
    }

    /** Adds what was counted since the last time into [stats]. */
    private fun fold() {
        val received = receivedDelta.getAndSet(0)
        val mentions = mentionDelta.getAndSet(0)
        if (received == 0L && mentions == 0L) return
        _stats.update { it.copy(received = it.received + received, mentions = it.mentions + mentions) }
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
