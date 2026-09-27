package dev.chatter.app.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** A channel that shares its chat with one the user is in, as its messages are marked with it. */
data class ChatPartner(val id: String, val login: String, val displayName: String, val avatarUrl: String?)

/**
 * The Shared Chat sessions of the channels the user is in, and who the channels in them are.
 *
 * Twitch says nothing over IRC when a session starts or ends; only the messages change. While one
 * runs, every message carries `source-room-id` — the channel's own messages naming the channel
 * itself — and once it is over they stop. So the first live message with the tag is the start of
 * a session and the first one without it is the end.
 *
 * Who else is in a session is asked of Helix once it starts, because a partner nobody has written
 * in yet has sent no message to be recognised by. Until Helix answers, and if it never does, the
 * partners are the channels whose messages have arrived.
 *
 * Touched on the chat worker; [sessions] and [partners] are what the screen reads.
 */
class SharedChats {
    private val _sessions = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    /** The partners' ids of every channel sharing its chat right now. A channel not in it is not. */
    val sessions: StateFlow<Map<String, List<String>>> = _sessions

    private val _partners = MutableStateFlow<Map<String, ChatPartner>>(emptyMap())
    /** Every partner described so far, by channel id. */
    val partners: StateFlow<Map<String, ChatPartner>> = _partners

    /** Ids looked up already, so that a busy partner is not asked about once per message. */
    private val asked = HashSet<String>()

    /**
     * A live message arrived in [channel], whose own id is [roomId], written in [sourceRoomId].
     * True for the message that started a session, which is when to ask who is in it.
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
     * Who Helix says takes part in the session of [channel], the channel itself ([roomId]) among
     * them. An empty answer changes nothing: the messages say there is a session, and Helix can
     * be a moment behind them.
     */
    fun setParticipants(channel: String, roomId: String, ids: List<String>) {
        val others = ids.filter { it != roomId }
        if (others.isEmpty()) return
        // Only while it lasts: the session may have ended while Helix was being asked.
        _sessions.update { if (channel in it) it + (channel to others) else it }
    }

    /** Of [ids], the ones nobody has looked up yet. They count as looked up from now on. */
    fun unknown(ids: Collection<String>): List<String> =
        ids.filter { it !in _partners.value && asked.add(it) }

    fun described(partners: List<ChatPartner>) {
        if (partners.isNotEmpty()) _partners.update { it + partners.associateBy(ChatPartner::id) }
    }

    /** A lookup that did not work out is tried again with the next message that needs it. */
    fun failed(ids: Collection<String>) {
        asked.removeAll(ids.toSet())
    }

    /** The channel was left; whether it shares its chat is nothing to show any more. */
    fun forget(channel: String) = _sessions.update { it - channel }

    /**
     * Every session, e.g. after logout. The partners stay: they are facts about Twitch channels
     * and do not change with who is logged in.
     */
    fun clear() {
        _sessions.value = emptyMap()
    }
}
