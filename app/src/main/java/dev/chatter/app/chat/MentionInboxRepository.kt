package dev.chatter.app.chat

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chatter.app.net.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    /** The user id of the account that was mentioned; null for rows from before inboxes knew. */
    val owner: String? = null,
)

/**
 * Every mention the user has ever received, across all channels, in one list that survives
 * restarts. The chat buffers are short-lived and per channel, so this is the only place where
 * "who wanted something from me yesterday" can still be answered.
 */
class MentionInboxRepository(
    private val store: DataStore<Preferences>,
    /** The user id of the account the app acts as; its mentions are the only ones shown. */
    private val owner: StateFlow<String?>,
    scope: CoroutineScope,
) {
    /** The active account's mentions, newest first. See [InboxOwners]. */
    val mentions: StateFlow<List<InboxMention>> = combine(store.data, owner) { p, me ->
        if (me == null) emptyList() else decode(p[MENTIONS]).filter { it.owner == me }
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

    /** Leaves the store alone when all of [ids] are read already, as they usually are. */
    suspend fun markRead(ids: Set<String>) = update { list ->
        if (list.none { !it.read && it.id in ids }) list
        else list.map { if (it.id in ids) it.copy(read = true) else it }
    }

    /** Called when a channel is opened: its mentions have been seen by definition. */
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

    /** Empties the active account's inbox; the other accounts keep theirs. */
    suspend fun clear() = update { list ->
        val me = owner.value
        if (list.none { it.owner == me }) list else list.filterNot { it.owner == me }
    }

    /** Lets go of the mentions of every account that is not in [accounts]; see [InboxOwners]. */
    suspend fun keepOnly(accounts: Collection<String>) = update { list ->
        InboxOwners.keepOnly(list, accounts, { it.owner }) { row, id -> row.copy(owner = id) }
    }

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
