package dev.chatter.app.stats

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chatter.app.net.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.LocalDate

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
class StatsRepository(private val store: DataStore<Preferences>, private val scope: CoroutineScope) {
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats

    fun start() {
        scope.launch {
            var saved = load()
            _stats.value = saved
            while (isActive) {
                delay(SAVE_INTERVAL_MS)
                val current = _stats.value
                if (current != saved) {
                    save(current)
                    saved = current
                }
            }
        }
    }

    /** Counts a message the user sent, and marks today as a day they were around. */
    fun countSent(channel: String) {
        val today = LocalDate.now().toString()
        _stats.update {
            it.copy(
                sent = it.sent + 1,
                sentPerChannel = it.sentPerChannel + (channel to (it.sentPerChannel[channel] ?: 0) + 1),
                activeDays = if (today in it.activeDays) it.activeDays else it.activeDays + today,
            )
        }
    }

    /** Counts a message that arrived live. History loaded on join is not new and does not count. */
    fun countReceived() = _stats.update { it.copy(received = it.received + 1) }

    fun countMention() = _stats.update { it.copy(mentions = it.mentions + 1) }

    /** Throws everything counted so far away and starts over from now. */
    suspend fun reset() {
        val fresh = Stats(since = System.currentTimeMillis())
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

        fun decode(raw: String?): Stats? =
            raw?.let { runCatching { AppJson.decodeFromString<Stats>(it) }.getOrNull() }
    }
}
