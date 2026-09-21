package dev.chatter.app.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * What Twitch says about the channels the user is in, and about the user's place in them.
 *
 * Twitch spreads this over three commands. ROOMSTATE brings the id of a channel and the modes it
 * is in (slow, followers-only) and is also the answer to a JOIN, so it is the moment a channel is
 * ready to be written in. USERSTATE says who the user is in that channel, by way of the badges
 * they wear there. GLOBALUSERSTATE says the same for everywhere else, and stands in wherever a
 * channel has not answered yet.
 *
 * The ids are read from other threads (a room id turns a notification into a channel), so they
 * live in a concurrent map; everything else is touched on the chat worker only.
 */
class Rooms {
    private val ids = ConcurrentHashMap<String, String>()
    private val userStates = HashMap<String, Map<String, String>>()
    private var globalUserState: Map<String, String> = emptyMap()

    private val _states = MutableStateFlow<Map<String, RoomState>>(emptyMap())
    /** Active chat modes (slow, followers-only, ...) per channel. */
    val states: StateFlow<Map<String, RoomState>> = _states

    private val _roles = MutableStateFlow<Map<String, ChatRole>>(emptyMap())
    /** The user's role (VIP, moderator, broadcaster) per channel. */
    val roles: StateFlow<Map<String, ChatRole>> = _roles

    private val _moderated = MutableStateFlow<Set<String>>(emptySet())
    /** Channels where the user is moderator or broadcaster. */
    val moderated: StateFlow<Set<String>> = _moderated

    private val _ready = MutableStateFlow<Set<String>>(emptySet())
    /**
     * Channels Twitch has confirmed the join for. Its ROOMSTATE is the answer to a JOIN, so it is
     * the first moment a message sent to that channel is actually accepted.
     */
    val ready: StateFlow<Set<String>> = _ready

    /** The Twitch id of a channel, once anybody has told us. */
    fun id(channel: String): String? = ids[channel]

    /** The channel an id belongs to — what a notification or a 7TV event comes back as. */
    fun channelOf(id: String): String? = ids.entries.firstOrNull { it.value == id }?.key

    fun knownIds(): List<String> = ids.values.toList()

    /** Returns true if this is the first time we hear the id, which is when to load its emotes. */
    fun setId(channel: String, id: String): Boolean = ids.put(channel, id) == null

    /** The tags to send a message in this channel under; the global ones until it has answered. */
    fun userState(channel: String): Map<String, String> = userStates[channel] ?: globalUserState

    /** Whether Twitch lets the user talk past slow mode and the like here. */
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
    }

    fun onGlobalUserState(tags: Map<String, String>) {
        globalUserState = tags
    }

    /** The channel was left: what Twitch said about it is no longer ours to remember. */
    fun forget(channel: String) {
        userStates.remove(channel)
        _states.update { it - channel }
        _roles.update { it - channel }
        _moderated.update { it - channel }
        _ready.update { it - channel }
    }

    /**
     * Everything, e.g. after logout. The ids stay: they are facts about Twitch channels, they
     * cost a request to learn, and they do not change with who is logged in.
     */
    fun clear() {
        userStates.clear()
        globalUserState = emptyMap()
        _states.value = emptyMap()
        _roles.value = emptyMap()
        _moderated.value = emptySet()
        _ready.value = emptySet()
    }
}
