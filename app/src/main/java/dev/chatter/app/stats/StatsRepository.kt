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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    fun start() {
        scope.launch {
            var saved = load()
            _stats.value = saved
            while (isActive) {
                // Wait for something to count, then let the next half minute of it pile up.
                counted.receive()
                delay(SAVE_INTERVAL_MS)
                fold()
                val current = _stats.value
                if (current != saved) {
                    save(current)
                    saved = current
                }
            }
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
        receivedDelta.set(0)
        mentionDelta.set(0)
        _stats.value = fresh
        save(fresh)
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
        /** How much counting a sudden death of the process may cost. */
        const val SAVE_INTERVAL_MS = 30_000L

        /** How often a screen showing the stats sees them move. */
        const val LIVE_INTERVAL_MS = 250L


        fun decode(raw: String?): Stats? =
            raw?.let { runCatching { AppJson.decodeFromString<Stats>(it) }.getOrNull() }
    }
}
