package me.lesh.chatter.chat

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import me.lesh.chatter.irc.IrcMessage
import me.lesh.chatter.net.AppJson
import me.lesh.chatter.net.decodeStored
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable

/**
 * A whisper the user received or sent, kept like a mention. For a sent one, [login],
 * [displayName] and [userId] are the recipient's.
 */
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
    /** Sent by the user. Twitch keeps no history to read, so this is the only record of it. */
    val outgoing: Boolean = false,
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
    /** Every account on the phone; see [me.lesh.chatter.auth.AuthRepository.knownUserIds]. */
    private val accounts: StateFlow<List<String>>,
    scope: CoroutineScope,
) {
    /**
     * The active account's whispers, newest first; see [InboxOwners]. Decoded only when they
     * changed: the mentions share the store.
     */
    val whispers: StateFlow<List<InboxWhisper>> = combine(
        store.data.map { it[WHISPERS] }.distinctUntilChanged().map { decode(it).orEmpty() },
        owner,
    ) { all, me ->
        if (me == null) emptyList() else all.filter { it.owner == me }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** One per person, the most recent first. */
    val conversations: StateFlow<List<WhisperConversation>> = whispers
        .map(::conversationsOf)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val unreadCount: StateFlow<Int> = whispers
        .map { list -> list.count { !it.read } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    /**
     * Keeps [whisper] for its [InboxWhisper.owner], whichever account is active by now. Checked in
     * the same write as the store, so an account removed meanwhile gets nothing: its
     * [keepOnly] has run already, or will run after this.
     */
    suspend fun add(whisper: InboxWhisper) = update { list ->
        val account = whisper.owner
        when {
            account == null || account !in accounts.value -> list
            // Whisper ids are per thread, so the same id can occur for another account.
            list.any { it.id == whisper.id && it.owner == account } -> list
            else -> (listOf(whisper) + list).take(LIMIT)
        }
    }

    /**
     * Keeps a whisper [owner] sent to [login]. Without [displayName] it takes the one their own
     * whispers had, so a conversation keeps one spelling.
     */
    suspend fun addSent(owner: String, login: String, userId: String?, displayName: String?, text: String, timestamp: Long) =
        update { list ->
            if (owner !in accounts.value) return@update list
            val name = displayName
                ?: list.firstOrNull { it.owner == owner && it.login.equals(login, ignoreCase = true) && !it.outgoing }?.displayName
                ?: login
            val sent = InboxWhisper(
                id = "sent-$timestamp-${list.size}",
                login = login.lowercase(),
                userId = userId,
                displayName = name,
                text = text,
                timestamp = timestamp,
                read = true,
                owner = owner,
                outgoing = true,
            )
            (listOf(sent) + list).take(LIMIT)
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

    /** Reads everything [login] sent the active account. */
    suspend fun markConversationRead(login: String) = update { list ->
        val me = owner.value
        val inConversation = { w: InboxWhisper -> w.owner == me && !w.read && w.login.equals(login, ignoreCase = true) }
        if (list.none(inConversation)) list else list.map { if (inConversation(it)) it.copy(read = true) else it }
    }

    /** Deletes the active account's conversation with [login]. */
    suspend fun clearConversation(login: String) = update { list ->
        val me = owner.value
        val inConversation = { w: InboxWhisper -> w.owner == me && w.login.equals(login, ignoreCase = true) }
        if (list.none(inConversation)) list else list.filterNot(inConversation)
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
        /** Whispers are rare; with both sides kept this is still months' worth. */
        const val LIMIT = 500

        fun decode(raw: String?): List<InboxWhisper>? = decodeStored(raw, emptyList(), "whispers")
    }
}
