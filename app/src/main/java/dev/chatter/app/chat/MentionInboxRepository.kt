package dev.chatter.app.chat

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chatter.app.net.AppJson
import dev.chatter.app.net.decodeStored
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** One mention, kept after the message has left the buffer. */
@Serializable
data class InboxMention(
    val id: String,
    val channel: String,
    val login: String,
    val displayName: String,
    val text: String,
    val timestamp: Long,
    val read: Boolean = false,
    /** The mentioned account's user id; null for rows from before owners were stored. */
    val owner: String? = null,
)

/**
 * Every mention received, across channels, kept across restarts.
 *
 * The list lives in memory and is written on a timer: a mention can arrive many times a minute all
 * night, and each write rewrites the whole list. On screen it is saved within seconds, in the
 * background once a minute, and at once through [saveNow] when the process may end.
 */
class MentionInboxRepository(
    private val store: DataStore<Preferences>,
    /** The active account's user id; only its mentions are shown. */
    private val owner: StateFlow<String?>,
    private val scope: CoroutineScope,
    /** Whether the app is on screen, which decides how soon a change is saved. */
    private val inFront: () -> Boolean = { true },
) {
    /**
     * Every account's mentions, newest first. Null until read from the store, and for good if the
     * stored list cannot be read: then nothing is added, so nothing is written over it.
     */
    private val all = MutableStateFlow<List<InboxMention>?>(null)
    private val loaded = CompletableDeferred<Unit>()
    private val changing = Mutex()

    /** What is on disk, to skip writes that change nothing. */
    private var saved: List<InboxMention>? = null
    private val saving = Mutex()
    private val changed = Channel<Unit>(Channel.CONFLATED)

    /** The active account's mentions, newest first. See [InboxOwners]. */
    val mentions: StateFlow<List<InboxMention>> = combine(all, owner) { list, me ->
        if (me == null) emptyList() else list.orEmpty().filter { it.owner == me }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val unreadCount: StateFlow<Int> = mentions
        .map { list -> list.count { !it.read } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    /** [account] is the user id the mention was for. */
    suspend fun add(item: ChatItem, read: Boolean, account: String) {
        val mention = InboxMention(
            id = item.id,
            channel = item.channel,
            login = item.login.orEmpty(),
            displayName = item.displayName ?: item.login.orEmpty(),
            text = item.text,
            timestamp = item.timestamp,
            read = read,
            owner = account,
        )
        update { list ->
            if (list.any { it.id == mention.id }) list else (listOf(mention) + list).take(LIMIT)
        }
    }

    suspend fun markRead(id: String) = markRead(setOf(id))

    /** Leaves the store alone when all [ids] are already read, the usual case. */
    suspend fun markRead(ids: Set<String>) = update { list ->
        if (list.none { !it.read && it.id in ids }) list
        else list.map { if (it.id in ids) it.copy(read = true) else it }
    }

    /** Called when a channel is opened; its mentions count as seen. */
    suspend fun markChannelRead(channel: String) = update { list ->
        val me = owner.value
        if (list.none { it.owner == me && it.channel == channel && !it.read }) list
        else list.map { if (it.owner == me && it.channel == channel) it.copy(read = true) else it }
    }

    suspend fun markAllRead() = update { list ->
        val me = owner.value
        if (list.none { it.owner == me && !it.read }) list
        else list.map { if (it.owner == me) it.copy(read = true) else it }
    }

    suspend fun remove(id: String) = update { list -> list.filterNot { it.id == id } }

    /** Empties the active account's inbox; other accounts keep theirs. */
    suspend fun clear() = update { list ->
        val me = owner.value
        if (list.none { it.owner == me }) list else list.filterNot { it.owner == me }
    }

    /** Drops the mentions of accounts not in [accounts]; see [InboxOwners]. */
    suspend fun keepOnly(accounts: Collection<String>) = update { list ->
        InboxOwners.keepOnly(list, accounts, { it.owner }) { row, id -> row.copy(owner = id) }
    }

    init {
        scope.launch {
            val stored = decode(store.data.first()[MENTIONS])
            saved = stored
            all.value = stored
            loaded.complete(Unit)
            while (isActive) {
                // Wait for a change, then let more pile up.
                changed.receive()
                delay(if (inFront()) SAVE_DELAY_MS else BACKGROUND_SAVE_DELAY_MS)
                save()
            }
        }
    }

    /** Writes what changed now, for moments the process may end. */
    fun saveNow() {
        scope.launch { save() }
    }

    /** Never changes an inbox it cannot read; see [decodeStored]. */
    private suspend fun update(transform: (List<InboxMention>) -> List<InboxMention>) {
        loaded.await()
        changing.withLock {
            val current = all.value ?: return
            val next = transform(current)
            if (next === current) return
            all.value = next
        }
        changed.trySend(Unit)
    }

    private suspend fun save() = saving.withLock {
        val current = all.value ?: return@withLock
        if (current === saved) return@withLock
        store.edit { it[MENTIONS] = AppJson.encodeToString(current) }
        saved = current
    }

    private companion object {
        val MENTIONS = stringPreferencesKey("inbox_mentions")
        /** A few days' worth without turning the store into a database. */
        const val LIMIT = 300

        const val SAVE_DELAY_MS = 2_000L
        const val BACKGROUND_SAVE_DELAY_MS = 60_000L

        fun decode(raw: String?): List<InboxMention>? = decodeStored(raw, emptyList(), "mentions")
    }
}
