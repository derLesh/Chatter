package dev.chatter.app.chat

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chatter.app.irc.IrcMessage
import dev.chatter.app.net.AppJson
import dev.chatter.app.net.decodeStored
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable

/** A whisper the user received, kept like a mention. */
@Serializable
data class InboxWhisper(
    val id: String,
    val login: String,
    /** The sender's Twitch id, needed to answer. Null for older whispers. */
    val userId: String? = null,
    val displayName: String,
    val text: String,
    val timestamp: Long,
    /** ARGB color from the `color` tag, or null if the sender never picked one. */
    val color: Int? = null,
    val read: Boolean = false,
    /** The receiving account's user id; null for rows from before owners were stored. */
    val owner: String? = null,
) {
    companion object {
        /**
         * Reads a `WHISPER` line. Whispers belong to no channel and only go into the inbox. Twitch
         * numbers whispers per thread, so thread and number together identify one.
         */
        fun from(msg: IrcMessage): InboxWhisper? {
            val login = msg.nick ?: return null
            val text = msg.trailing ?: return null
            val thread = msg.tag("thread-id") ?: login
            val number = msg.tag("message-id") ?: msg.tag("tmi-sent-ts") ?: System.nanoTime().toString()
            return InboxWhisper(
                id = "$thread-$number",
                login = login,
                userId = msg.tag("user-id"),
                displayName = msg.tag("display-name") ?: login,
                text = text,
                // Whispers carry no send time; they arrive as they are written.
                timestamp = System.currentTimeMillis(),
                color = MessageBuilder.parseColor(msg.tag("color")),
            )
        }
    }
}

/** Every whisper received, newest first, kept across restarts. */
class WhisperInboxRepository(
    private val store: DataStore<Preferences>,
    /** The active account's user id; only its whispers are shown. */
    private val owner: StateFlow<String?>,
    scope: CoroutineScope,
) {
    /** The active account's whispers, newest first; see [InboxOwners]. */
    val whispers: StateFlow<List<InboxWhisper>> = combine(store.data, owner) { p, me ->
        if (me == null) emptyList() else decode(p[WHISPERS]).orEmpty().filter { it.owner == me }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val unreadCount: StateFlow<Int> = whispers
        .map { list -> list.count { !it.read } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    /** [account] is the user id the whisper was sent to. */
    suspend fun add(whisper: InboxWhisper, account: String) = update { list ->
        // Whisper ids are per thread, so the same id can occur for another account.
        if (list.any { it.id == whisper.id && it.owner == account }) list
        else (listOf(whisper.copy(owner = account)) + list).take(LIMIT)
    }

    suspend fun markRead(id: String) = update { list ->
        val me = owner.value
        list.map { if (it.id == id && it.owner == me) it.copy(read = true) else it }
    }

    suspend fun markAllRead() = update { list ->
        val me = owner.value
        if (list.none { it.owner == me && !it.read }) list
        else list.map { if (it.owner == me) it.copy(read = true) else it }
    }

    /** Empties the active account's inbox; other accounts keep theirs. */
    suspend fun clear() = update { list ->
        val me = owner.value
        if (list.none { it.owner == me }) list else list.filterNot { it.owner == me }
    }

    /** Drops the whispers of accounts not in [accounts]; see [InboxOwners]. */
    suspend fun keepOnly(accounts: Collection<String>) = update { list ->
        InboxOwners.keepOnly(list, accounts, { it.owner }) { row, id -> row.copy(owner = id) }
    }

    /** Never writes over an inbox it cannot read; see [decodeStored]. */
    private suspend fun update(transform: (List<InboxWhisper>) -> List<InboxWhisper>) {
        store.edit { p ->
            val current = decode(p[WHISPERS]) ?: return@edit
            val next = transform(current)
            if (next !== current) p[WHISPERS] = AppJson.encodeToString(next)
        }
    }

    private companion object {
        val WHISPERS = stringPreferencesKey("inbox_whispers")
        /** Whispers are rare; this is months' worth. */
        const val LIMIT = 200

        fun decode(raw: String?): List<InboxWhisper>? = decodeStored(raw, emptyList(), "whispers")
    }
}
