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

/** One whisper somebody sent the user, kept the same way a mention is. */
@Serializable
data class InboxWhisper(
    val id: String,
    val login: String,
    /** The sender's Twitch id, which is what answering them needs. Null for older whispers. */
    val userId: String? = null,
    val displayName: String,
    val text: String,
    val timestamp: Long,
    /** ARGB color from the `color` tag, or null if the sender never picked one. */
    val color: Int? = null,
    val read: Boolean = false,
    /** The user id of the account it was sent to; null for rows from before inboxes knew. */
    val owner: String? = null,
) {
    companion object {
        /**
         * Reads a `WHISPER` line. Whispers belong to no channel, so nothing about them fits the
         * channel buffers — they are only ever a row in the inbox.
         *
         * Twitch numbers whispers per thread rather than globally, so the thread and the number
         * together are what tells two of them apart.
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
                // A whisper carries no send time of its own; it arrives the moment it is written.
                timestamp = System.currentTimeMillis(),
                color = MessageBuilder.parseColor(msg.tag("color")),
            )
        }
    }
}

/**
 * Every whisper the user has received, newest first and surviving restarts. Chatter cannot send
 * whispers (Twitch dropped that from chat), so this is a mailbox to read, not a conversation.
 */
class WhisperInboxRepository(
    private val store: DataStore<Preferences>,
    /** The user id of the account the app acts as; its whispers are the only ones shown. */
    private val owner: StateFlow<String?>,
    scope: CoroutineScope,
) {
    /** The active account's whispers, newest first. See [InboxOwners]. */
    val whispers: StateFlow<List<InboxWhisper>> = combine(store.data, owner) { p, me ->
        if (me == null) emptyList() else decode(p[WHISPERS]).orEmpty().filter { it.owner == me }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val unreadCount: StateFlow<Int> = whispers
        .map { list -> list.count { !it.read } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    /** [account] is the user id the whisper was sent to. */
    suspend fun add(whisper: InboxWhisper, account: String) = update { list ->
        // Twitch numbers whispers per thread, so the same id can turn up for another account.
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

    /** Empties the active account's inbox; the other accounts keep theirs. */
    suspend fun clear() = update { list ->
        val me = owner.value
        if (list.none { it.owner == me }) list else list.filterNot { it.owner == me }
    }

    /** Lets go of the whispers of every account that is not in [accounts]; see [InboxOwners]. */
    suspend fun keepOnly(accounts: Collection<String>) = update { list ->
        InboxOwners.keepOnly(list, accounts, { it.owner }) { row, id -> row.copy(owner = id) }
    }

    /** Leaves an inbox it cannot read alone rather than write over it; see [decodeStored]. */
    private suspend fun update(transform: (List<InboxWhisper>) -> List<InboxWhisper>) {
        store.edit { p ->
            val current = decode(p[WHISPERS]) ?: return@edit
            val next = transform(current)
            if (next !== current) p[WHISPERS] = AppJson.encodeToString(next)
        }
    }

    private companion object {
        val WHISPERS = stringPreferencesKey("inbox_whispers")
        /** Whispers are rare next to mentions; this is already months of them. */
        const val LIMIT = 200

        fun decode(raw: String?): List<InboxWhisper>? = decodeStored(raw, emptyList(), "whispers")
    }
}
