package dev.chatter.app.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** A channel sharing its chat with a joined one, as its messages are marked. */
data class ChatPartner(val id: String, val login: String, val displayName: String, val avatarUrl: String?)

/**
 * Shared Chat sessions of the joined channels and their partners.
 *
 * IRC says nothing when a session starts or ends. While one runs, every message carries
 * `source-room-id` (the channel's own messages name the channel itself), so the first live message
 * with the tag starts a session and the first without it ends it.
 *
 * When a session starts, Helix is asked for the partners, since a partner nobody has written in yet
 * sent no message. Until Helix answers, the partners are the channels whose messages arrived.
 *
 * Touched on the chat worker; the UI reads [sessions] and [partners].
 */
class SharedChats {
    private val _sessions = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    /** Partner ids of every channel currently sharing its chat. */
    val sessions: StateFlow<Map<String, List<String>>> = _sessions

    private val _partners = MutableStateFlow<Map<String, ChatPartner>>(emptyMap())
    /** All partners described so far, by channel id. */
    val partners: StateFlow<Map<String, ChatPartner>> = _partners

    /** Ids already looked up, so a busy partner is not looked up per message. */
    private val asked = HashSet<String>()

    /**
     * A live message in [channel] (id [roomId]), written in [sourceRoomId]. True for the message
     * that started a session, which is when to ask for the partners.
     */
    fun onLiveMessage(channel: String, roomId: String?, sourceRoomId: String?): Boolean {
        val current = _sessions.value[channel]
        if (sourceRoomId == null) {
            if (current != null) _sessions.update { it - channel }
            return false
        }
        val partner = sourceRoomId.takeIf { it != roomId }
        if (current != null && (partner == null || partner in current)) return false
        _sessions.update { it + (channel to (current.orEmpty() + listOfNotNull(partner))) }
        return current == null
    }

    /**
     * The session participants Helix reports for [channel], including the channel itself
     * ([roomId]). An empty answer changes nothing; Helix can lag behind the messages.
     */
    fun setParticipants(channel: String, roomId: String, ids: List<String>) {
        val others = ids.filter { it != roomId }
        if (others.isEmpty()) return
        // Only while the session lasts; it may have ended during the request.
        _sessions.update { if (channel in it) it + (channel to others) else it }
    }

    /** Those of [ids] not looked up yet; they count as looked up from now on. */
    fun unknown(ids: Collection<String>): List<String> =
        ids.filter { it !in _partners.value && asked.add(it) }

    fun described(partners: List<ChatPartner>) {
        if (partners.isNotEmpty()) _partners.update { it + partners.associateBy(ChatPartner::id) }
    }

    /** A failed lookup is retried with the next message that needs it. */
    fun failed(ids: Collection<String>) {
        asked.removeAll(ids.toSet())
    }

    /** The channel was left. */
    fun forget(channel: String) = _sessions.update { it - channel }

    /** Clears the sessions, e.g. after logout. Partners stay; they do not depend on the login. */
    fun clear() {
        _sessions.value = emptyMap()
    }
}
