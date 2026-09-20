package dev.chatter.app.chat

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chatter.app.irc.IrcMessage
import dev.chatter.app.net.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
class WhisperInboxRepository(private val store: DataStore<Preferences>, scope: CoroutineScope) {
    val whispers: StateFlow<List<InboxWhisper>> = store.data
        .map { p -> decode(p[WHISPERS]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val unreadCount: StateFlow<Int> = whispers
        .map { list -> list.count { !it.read } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    suspend fun add(whisper: InboxWhisper) = update { list ->
        if (list.any { it.id == whisper.id }) list else (listOf(whisper) + list).take(LIMIT)
    }

    suspend fun markRead(id: String) = update { list ->
        list.map { if (it.id == id) it.copy(read = true) else it }
    }

    suspend fun markAllRead() = update { list ->
        if (list.none { !it.read }) list else list.map { it.copy(read = true) }
    }

    suspend fun clear() = update { emptyList() }

    private suspend fun update(transform: (List<InboxWhisper>) -> List<InboxWhisper>) {
        store.edit { p ->
            val current = decode(p[WHISPERS])
            val next = transform(current)
            if (next !== current) p[WHISPERS] = AppJson.encodeToString(next)
        }
    }

    private companion object {
        val WHISPERS = stringPreferencesKey("inbox_whispers")
        /** Whispers are rare next to mentions; this is already months of them. */
        const val LIMIT = 200

        fun decode(raw: String?): List<InboxWhisper> =
            raw?.let { runCatching { AppJson.decodeFromString<List<InboxWhisper>>(it) }.getOrNull() }.orEmpty()
    }
}
