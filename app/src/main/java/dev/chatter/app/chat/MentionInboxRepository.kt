package dev.chatter.app.chat

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chatter.app.net.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable

/** One mention, kept after the message itself has long scrolled out of the buffer. */
@Serializable
data class InboxMention(
    val id: String,
    val channel: String,
    val login: String,
    val displayName: String,
    val text: String,
    val timestamp: Long,
    val read: Boolean = false,
)

/**
 * Every mention the user has ever received, across all channels, in one list that survives
 * restarts. The chat buffers are short-lived and per channel, so this is the only place where
 * "who wanted something from me yesterday" can still be answered.
 */
class MentionInboxRepository(private val store: DataStore<Preferences>, scope: CoroutineScope) {
    /** Newest first. */
    val mentions: StateFlow<List<InboxMention>> = store.data
        .map { p -> decode(p[MENTIONS]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val unreadCount: StateFlow<Int> = mentions
        .map { list -> list.count { !it.read } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    suspend fun add(item: ChatItem, read: Boolean) {
        val mention = InboxMention(
            id = item.id,
            channel = item.channel,
            login = item.login.orEmpty(),
            displayName = item.displayName ?: item.login.orEmpty(),
            text = item.text,
            timestamp = item.timestamp,
            read = read,
        )
        update { list ->
            if (list.any { it.id == mention.id }) list else (listOf(mention) + list).take(LIMIT)
        }
    }

    suspend fun markRead(id: String) = update { list ->
        list.map { if (it.id == id) it.copy(read = true) else it }
    }

    /** Called when a channel is opened: its mentions have been seen by definition. */
    suspend fun markChannelRead(channel: String) = update { list ->
        if (list.none { it.channel == channel && !it.read }) list
        else list.map { if (it.channel == channel) it.copy(read = true) else it }
    }

    suspend fun markAllRead() = update { list ->
        if (list.none { !it.read }) list else list.map { it.copy(read = true) }
    }

    suspend fun remove(id: String) = update { list -> list.filterNot { it.id == id } }

    suspend fun clear() = update { emptyList() }

    private suspend fun update(transform: (List<InboxMention>) -> List<InboxMention>) {
        store.edit { p ->
            val current = decode(p[MENTIONS])
            val next = transform(current)
            if (next !== current) p[MENTIONS] = AppJson.encodeToString(next)
        }
    }

    private companion object {
        val MENTIONS = stringPreferencesKey("inbox_mentions")
        /** Enough to look back a few days without turning the store into a database. */
        const val LIMIT = 300

        fun decode(raw: String?): List<InboxMention> =
            raw?.let { runCatching { AppJson.decodeFromString<List<InboxMention>>(it) }.getOrNull() }.orEmpty()
    }
}
