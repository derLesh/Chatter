package dev.chatter.app.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * What Twitch reports about the joined channels and the user's place in them.
 *
 * ROOMSTATE carries a channel's id and modes and answers a JOIN, so it marks a channel as ready to
 * write in. USERSTATE gives the user's badges in a channel; GLOBALUSERSTATE stands in until a
 * channel has answered.
 *
 * Ids are read from other threads (a room id resolves a notification to a channel), so they are in
 * a concurrent map; everything else is touched on the chat worker only.
 */
class Rooms {
    private val ids = ConcurrentHashMap<String, String>()
    private val userStates = HashMap<String, Map<String, String>>()
    private var globalUserState: Map<String, String> = emptyMap()

    private val _states = MutableStateFlow<Map<String, RoomState>>(emptyMap())
    /** Active chat modes per channel. */
    val states: StateFlow<Map<String, RoomState>> = _states

    private val _roles = MutableStateFlow<Map<String, ChatRole>>(emptyMap())
    /** The user's role per channel. */
    val roles: StateFlow<Map<String, ChatRole>> = _roles

    private val _moderated = MutableStateFlow<Set<String>>(emptySet())
    /** Channels where the user is moderator or broadcaster. */
    val moderated: StateFlow<Set<String>> = _moderated

    private val _subscribed = MutableStateFlow<Set<String>>(emptySet())
    /** Channels the user is subscribed to, judging by their badges there. */
    val subscribed: StateFlow<Set<String>> = _subscribed

    private val _ready = MutableStateFlow<Set<String>>(emptySet())
    /**
     * Channels whose JOIN Twitch confirmed with a ROOMSTATE; from then on messages are accepted.
     */
    val ready: StateFlow<Set<String>> = _ready

    /** The Twitch id of a channel, once known. */
    fun id(channel: String): String? = ids[channel]

    /** The channel an id belongs to, e.g. for a notification or a 7TV event. */
    fun channelOf(id: String): String? = ids.entries.firstOrNull { it.value == id }?.key

    fun knownIds(): List<String> = ids.values.toList()

    /** True the first time the id is set, which is when to load the channel's emotes. */
    fun setId(channel: String, id: String): Boolean = ids.put(channel, id) == null

    /** The tags to send with in this channel; the global ones until the channel answered. */
    fun userState(channel: String): Map<String, String> = userStates[channel] ?: globalUserState

    /** Whether the user may bypass slow mode and similar here. */
    fun isPrivileged(channel: String): Boolean =
        userState(channel)["badges"].orEmpty().split(',').any {
            it.startsWith("moderator/") || it.startsWith("broadcaster/") || it.startsWith("vip/")
        }

    fun onRoomState(channel: String, tags: Map<String, String>): Boolean {
        _states.update { it + (channel to (it[channel] ?: RoomState()).update(tags)) }
        _ready.update { it + channel }
        return tags["room-id"]?.let { setId(channel, it) } ?: false
    }

    fun onUserState(channel: String, tags: Map<String, String>) {
        userStates[channel] = tags
        val role = ChatRole.fromBadges(tags["badges"].orEmpty())
        val moderates = role == ChatRole.Moderator || role == ChatRole.Broadcaster
        _moderated.update { if (moderates) it + channel else it - channel }
        _roles.update { if (it[channel] == role) it else it + (channel to role) }
        // Founders count as subscribers while they wear the badge.
        val subscriber = tags["badges"].orEmpty().split(',').any { it.startsWith("subscriber/") || it.startsWith("founder/") }
        _subscribed.update { if (subscriber) it + channel else it - channel }
    }

    fun onGlobalUserState(tags: Map<String, String>) {
        globalUserState = tags
    }

    /** The channel was left; forget what Twitch said about it. */
    fun forget(channel: String) {
        userStates.remove(channel)
        _states.update { it - channel }
        _roles.update { it - channel }
        _moderated.update { it - channel }
        _subscribed.update { it - channel }
        _ready.update { it - channel }
    }

    /**
     * Clears everything, e.g. after logout, except the ids: they do not depend on the login and
     * cost a request to learn.
     */
    fun clear() {
        userStates.clear()
        globalUserState = emptyMap()
        _states.value = emptyMap()
        _roles.value = emptyMap()
        _moderated.value = emptySet()
        _subscribed.value = emptySet()
        _ready.value = emptySet()
    }
}
