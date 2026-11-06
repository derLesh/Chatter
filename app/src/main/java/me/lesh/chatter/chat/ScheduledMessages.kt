package me.lesh.chatter.chat

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import me.lesh.chatter.net.AppJson
import me.lesh.chatter.net.decodeStored
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** A message written now to go out in [channel] at [sendAt] (epoch ms), as [account] (a user id). */
@Serializable
data class ScheduledMessage(
    val id: String,
    val account: String,
    val channel: String,
    val text: String,
    val sendAt: Long,
    val reply: SavedReply? = null,
    /** It did not go out, and waits for the user to take it back or drop it. */
    val failed: Boolean = false,
)

/** Where [ScheduledMessages] keeps its list, so it outlives the process. */
interface ScheduledStore {
    val messages: Flow<List<ScheduledMessage>>

    suspend fun update(change: (List<ScheduledMessage>) -> List<ScheduledMessage>)
}

class PreferencesScheduledStore(private val store: DataStore<Preferences>) : ScheduledStore {
    override val messages: Flow<List<ScheduledMessage>> = store.data.map { decode(it[KEY]).orEmpty() }

    override suspend fun update(change: (List<ScheduledMessage>) -> List<ScheduledMessage>) {
        store.edit { p ->
            // Never writes over a list it cannot read; see decodeStored.
            val all = decode(p[KEY]) ?: return@edit
            p[KEY] = AppJson.encodeToString(change(all))
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("scheduled_messages")

        fun decode(raw: String?): List<ScheduledMessage>? = decodeStored(raw, emptyList(), "scheduled messages")
    }
}

/**
 * Messages the user planned to send later, and the clock that sends them.
 *
 * The wait runs on a clock that stops while the phone sleeps, so it is exact to the millisecond
 * only while the phone is awake, as it is with Chatter open. For the background [alarm] is asked
 * to wake the phone ([wakeAt] says when), and its wake-up calls [wakeUp] and [sendDue].
 *
 * A message is taken off the list before it goes out, so it is never sent twice. One that cannot
 * go out comes back marked as failed, and so does one Twitch refuses right after ([onRefused]).
 */
class ScheduledMessages(
    private val store: ScheduledStore,
    private val scope: CoroutineScope,
    private val deliver: suspend (ScheduledMessage) -> SendResult,
    private val alarm: (wakeAt: Long?) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val messages: StateFlow<List<ScheduledMessage>> = store.messages.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _failures = MutableSharedFlow<ScheduledMessage>(extraBufferCapacity = 8)
    /** Messages that did not go out, as they fail. */
    val failures: SharedFlow<ScheduledMessage> = _failures

    private val nextDue: Flow<Long?> = messages
        .map { all -> all.filterNot { it.failed }.minOfOrNull { it.sendAt } }
        .distinctUntilChanged()

    private val wakeUps = MutableStateFlow(0)
    private val sending = Mutex()

    /** The last message sent per channel and when, in case Twitch refuses it. */
    private val justSent = HashMap<String, Pair<ScheduledMessage, Long>>()

    fun start() {
        scope.launch {
            combine(nextDue, wakeUps) { next, _ -> next }.collectLatest { next ->
                alarm(next?.let { wakeAt(it, now()) })
                if (next == null) return@collectLatest
                while (true) {
                    val left = next - now()
                    if (left <= 0) break
                    delay(left)
                }
                // Launched apart: sending changes the list, and that restarts this block.
                scope.launch { sendDue() }
            }
        }
    }

    /** Looks at the clock again, after the phone may have slept. */
    fun wakeUp() = wakeUps.update { it + 1 }

    suspend fun add(account: String, channel: String, text: String, replyTo: ChatItem?, sendAt: Long) {
        val message = ScheduledMessage(UUID.randomUUID().toString(), account, channel, text, sendAt, replyTo?.let(SavedReply::of))
        store.update { it + message }
    }

    /** Takes [id] off the list; null if it is gone already, e.g. sent a moment ago. */
    suspend fun take(id: String): ScheduledMessage? {
        var taken: ScheduledMessage? = null
        store.update { all ->
            taken = all.firstOrNull { it.id == id }
            all.filterNot { it.id == id }
        }
        return taken
    }

    /** Sends every message whose time has come. */
    suspend fun sendDue() = sending.withLock {
        val at = now()
        var due = emptyList<ScheduledMessage>()
        store.update { all ->
            due = all.filter { !it.failed && it.sendAt <= at }
            all - due.toSet()
        }
        due.sortedBy { it.sendAt }.forEach { message ->
            // Hours late, e.g. after the phone was off, it would no longer fit the chat.
            val result = if (at - message.sendAt > MISSED_AFTER_MS) null else deliver(message)
            if (result == SendResult.Ok) {
                synchronized(justSent) { justSent[message.channel] = message to now() }
            } else {
                fail(message)
            }
        }
    }

    /** Twitch refused the last message sent in [channel]; if it was a planned one, it comes back. */
    fun onRefused(channel: String) {
        val (message, sentAt) = synchronized(justSent) { justSent.remove(channel) } ?: return
        if (now() - sentAt > REFUSAL_WINDOW_MS) return
        scope.launch { fail(message) }
    }

    private suspend fun fail(message: ScheduledMessage) {
        store.update { it + message.copy(failed = true) }
        _failures.tryEmit(message)
    }

    companion object {
        /** Later than this, a message is not sent but marked as failed. */
        const val MISSED_AFTER_MS = 15 * 60_000L

        /** Twitch answers within a second; the composer waits as long for its own messages. */
        private const val REFUSAL_WINDOW_MS = 10_000L

        /** Android sets an alarm this close to its time without moving it. */
        private const val UNMOVED_ALARM_MS = 10_000L

        /**
         * When to wake the phone for a message due at [next]. Android may move an alarm back by up
         * to three quarters of the time until it, so one far away is woken for early, at 4/7 of the
         * way: moved as far as it goes, it still comes in time, and the next one is set from there.
         */
        fun wakeAt(next: Long, now: Long): Long {
            val left = next - now
            return if (left <= UNMOVED_ALARM_MS) next else now + left * 4 / 7
        }

        /** The next time the clock in [zone] shows [hour]:[minute]:00, after [now]. */
        fun nextAt(hour: Int, minute: Int, now: Long, zone: ZoneId): Long {
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val time = LocalTime.of(hour, minute)
            val atToday = today.atTime(time).atZone(zone).toInstant().toEpochMilli()
            return if (atToday > now) atToday else today.plusDays(1).atTime(time).atZone(zone).toInstant().toEpochMilli()
        }
    }
}
