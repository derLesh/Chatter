package dev.chatter.app.channels

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chatter.app.net.AppJson
import dev.chatter.app.net.HelixApi
import dev.chatter.app.net.HelixChannelSearch
import dev.chatter.app.net.decodeStored
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ChannelInfo(
    val login: String,
    val id: String? = null,
    val displayName: String = login,
    val avatarUrl: String? = null,
    val isLive: Boolean = false,
    val viewers: Int = 0,
    val title: String = "",
    val game: String = "",
)

/**
 * What icons and shortcuts are built from. The live status is left out; it changes every two
 * minutes and would rebuild them each time.
 */
data class ChannelIdentity(val login: String, val name: String, val avatarUrl: String?)

/** The user's channel list (persisted, ordered), profile info, live status and combined chats. */
class ChannelRepository(
    private val store: DataStore<Preferences>,
    private val helix: HelixApi,
    scope: CoroutineScope,
) {
    /**
     * All chat pages in the user's order: channel logins and combined chat keys, in one list so a
     * combined chat can sit between channels and be moved like them.
     */
    val pages: StateFlow<List<String>> = store.data
        .map { p -> split(p[CHANNELS]).filter(::isPage) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The channels only: what is joined, notified and given a shortcut. Valid logins only, since
     * these names go into IRC commands.
     */
    val channels: StateFlow<List<String>> = store.data
        .map { p -> split(p[CHANNELS]).filterNot(ChannelGroup::isKey).filter { VALID.matches(it) } }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Combined chats by their key in [pages]. */
    val groups: StateFlow<Map<String, ChannelGroup>> = store.data
        .map { p -> decodeGroups(p[GROUPS]).orEmpty().associateBy { it.key } }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val _info = MutableStateFlow<Map<String, ChannelInfo>>(emptyMap())

    /** Custom channel names by login. */
    val customNames: StateFlow<Map<String, String>> = store.data
        .map { p -> decodeNames(p[CUSTOM_NAMES]).orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Profile info with custom names applied. */
    val info: StateFlow<Map<String, ChannelInfo>> = combine(_info, customNames) { info, names ->
        if (names.isEmpty()) info
        else info.mapValues { (login, i) -> names[login]?.let { i.copy(displayName = it) } ?: i }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /**
     * The channels in order with their name and picture. Shortcuts and notification channels are
     * built from this, so they only update when something visible changed.
     */
    val identities: StateFlow<List<ChannelIdentity>> = combine(channels, info) { list, info ->
        list.map { login ->
            val i = info[login]
            ChannelIdentity(login, i?.displayName ?: login, i?.avatarUrl)
        }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The channel last read, stored so the chat screen can return to it after process death. */
    val lastChannel: StateFlow<String?> = store.data
        .map { p -> p[LAST_CHANNEL] }
        .stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun setLastChannel(login: String) = store.edit { it[LAST_CHANNEL] = login }

    /** Channels with notifications off. */
    val mutedChannels: StateFlow<Set<String>> = store.data
        .map { p -> p[MUTED].orEmpty().split(',').filter { it.isNotEmpty() }.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Channels hidden from the unread strip in the title bar. */
    val hiddenUnread: StateFlow<Set<String>> = store.data
        .map { p -> p[NO_TITLE_BAR].orEmpty().split(',').filter { it.isNotEmpty() }.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    suspend fun setUnreadVisible(login: String, visible: Boolean) {
        store.edit { p ->
            val hidden = p[NO_TITLE_BAR].orEmpty().split(',').filter { it.isNotEmpty() }.toMutableSet()
            if (visible) hidden -= login else hidden += login
            p[NO_TITLE_BAR] = hidden.joinToString(",")
        }
    }

    suspend fun setNotify(login: String, enabled: Boolean) {
        store.edit { p ->
            val muted = p[MUTED].orEmpty().split(',').filter { it.isNotEmpty() }.toMutableSet()
            if (enabled) muted -= login else muted += login
            p[MUTED] = muted.joinToString(",")
        }
    }

    /** Loads cached profile info so avatars show right away. */
    suspend fun loadCache() {
        val raw = store.data.first()[INFO_CACHE] ?: return
        runCatching { AppJson.decodeFromString<List<ChannelInfo>>(raw) }
            .onSuccess { list -> _info.update { current -> list.associateBy { it.login } + current } }
    }

    suspend fun currentChannels(): List<String> = split(store.data.first()[CHANNELS]).filterNot(ChannelGroup::isKey)

    /** Returns the normalized login, or null if [input] is not a valid channel name. */
    suspend fun add(input: String): String? {
        val login = normalize(input) ?: return null
        store.edit { p ->
            val list = p[CHANNELS].orEmpty().split(',').filter { it.isNotEmpty() }
            if (login !in list) p[CHANNELS] = (list + login).joinToString(",")
        }
        refreshUsers(listOf(login))
        return login
    }

    /**
     * Removes a channel or combined chat with everything set on it. Returns what was removed, for
     * [putBack]; null if it was not in the list.
     */
    suspend fun remove(page: String): RemovedPage? {
        var removed: RemovedPage? = null
        store.edit { p ->
            val (lists, what) = p.lists()?.remove(page) ?: return@edit
            p.write(lists)
            removed = what
        }
        return removed
    }

    /** Undoes a [remove]. */
    suspend fun putBack(removed: RemovedPage) {
        store.edit { p -> p.lists()?.let { p.write(it.putBack(removed)) } }
    }

    /**
     * Null while combined chats or names cannot be read; writing back would overwrite them. See
     * [decodeStored].
     */
    private fun Preferences.lists(): ChannelLists? = ChannelLists(
        pages = split(this[CHANNELS]),
        groups = decodeGroups(this[GROUPS]) ?: return null,
        names = decodeNames(this[CUSTOM_NAMES]) ?: return null,
        muted = split(this[MUTED]).toSet(),
        hiddenUnread = split(this[NO_TITLE_BAR]).toSet(),
    )

    private fun MutablePreferences.write(lists: ChannelLists) {
        this[CHANNELS] = lists.pages.joinToString(",")
        this[GROUPS] = AppJson.encodeToString(lists.groups)
        this[CUSTOM_NAMES] = AppJson.encodeToString(lists.names)
        this[MUTED] = lists.muted.joinToString(",")
        this[NO_TITLE_BAR] = lists.hiddenUnread.joinToString(",")
    }

    /**
     * Creates a combined chat of [channels], or changes the one [key] names, and returns its key.
     * The channels are kept in list order, not in the order they were ticked.
     */
    suspend fun saveGroup(key: String?, name: String, channels: Collection<String>): String {
        val id = key?.let(ChannelGroup::idOf) ?: UUID.randomUUID().toString().take(8)
        val group = ChannelGroup(id, name.trim())
        store.edit { p ->
            val list = split(p[CHANNELS])
            val chosen = group.copy(channels = list.filter { it in channels && !ChannelGroup.isKey(it) })
            val groups = decodeGroups(p[GROUPS]) ?: return@edit
            p[GROUPS] = AppJson.encodeToString(
                if (groups.any { it.id == id }) groups.map { if (it.id == id) chosen else it } else groups + chosen
            )
            if (group.key !in list) p[CHANNELS] = (list + group.key).joinToString(",")
        }
        return group.key
    }

    /** Sets a custom channel name; blank restores the Twitch name. */
    suspend fun rename(login: String, name: String) {
        val chosen = name.trim()
        store.edit { p ->
            val names = decodeNames(p[CUSTOM_NAMES]) ?: return@edit
            p[CUSTOM_NAMES] = AppJson.encodeToString(if (chosen.isEmpty()) names - login else names + (login to chosen))
        }
    }

    /** The Twitch name, ignoring custom names, to show what a reset restores. */
    fun twitchName(login: String): String = _info.value[login]?.displayName ?: login

    /** Moves a channel or a combined chat [delta] places along the list of pages. */
    suspend fun move(page: String, delta: Int) {
        store.edit { p ->
            val list = split(p[CHANNELS]).toMutableList()
            val from = list.indexOf(page)
            val to = (from + delta).coerceIn(0, list.lastIndex)
            if (from >= 0 && from != to) {
                list.add(to, list.removeAt(from))
                p[CHANNELS] = list.joinToString(",")
            }
        }
    }

    /**
     * Replaces the channel list and everything set on it, for restoring a backup. Invalid logins
     * are dropped.
     *
     * [logins] may contain combined chat keys at their position; others go to the end. Channels of
     * a combined chat that are not in the list are dropped, and combined chats left empty are not
     * restored.
     */
    suspend fun restore(
        logins: List<String>,
        names: Map<String, String>,
        notificationsOff: Set<String>,
        hiddenUnread: Set<String>,
        groups: List<ChannelGroup> = emptyList(),
    ) {
        val valid = logins.filterNot(ChannelGroup::isKey).mapNotNull { normalize(it) }.distinct()
        val kept = groups.filter { ChannelGroup.isValidId(it.id) }.distinctBy { it.id }
            .map { g -> g.copy(channels = g.channels.mapNotNull { normalize(it) }.filter { it in valid }.distinct()) }
            .filter { it.channels.isNotEmpty() }
        val keys = kept.map { it.key }.toSet()
        val order = logins.mapNotNull { if (ChannelGroup.isKey(it)) it.takeIf { k -> k in keys } else normalize(it) }.distinct()
        store.edit { p ->
            p[CHANNELS] = (order + kept.map { it.key }.filter { it !in order }).joinToString(",")
            p[GROUPS] = AppJson.encodeToString(kept)
            p[CUSTOM_NAMES] = AppJson.encodeToString(names.filterKeys { it in valid })
            p[MUTED] = notificationsOff.filter { it in valid }.joinToString(",")
            p[NO_TITLE_BAR] = hiddenUnread.filter { it in valid }.joinToString(",")
        }
        refreshUsers(valid)
    }

    /** Fetches id, display name and avatar. Returns the Twitch user id per login. */
    suspend fun refreshUsers(logins: List<String>): Map<String, String> {
        if (logins.isEmpty()) return emptyMap()
        val users = try {
            helix.users(logins)
        } catch (e: Exception) {
            Log.w(TAG, "users failed: ${e.message}")
            return emptyMap()
        }
        _info.update { current ->
            current + users.associate { u ->
                val old = current[u.login] ?: ChannelInfo(u.login)
                u.login to old.copy(id = u.id, displayName = u.displayName, avatarUrl = u.profileImageUrl)
            }
        }
        saveCache()
        return users.associate { it.login to it.id }
    }

    /** One Helix call for all channels. Only while the app is in the foreground. */
    suspend fun refreshLive() {
        val logins = channels.value
        if (logins.isEmpty()) return
        val streams = try {
            helix.liveStreams(logins).associateBy { it.userLogin }
        } catch (e: Exception) {
            Log.w(TAG, "streams failed: ${e.message}")
            return
        }
        _info.update { current ->
            current + logins.associateWith { login ->
                val s = streams[login]
                (current[login] ?: ChannelInfo(login)).copy(
                    isLive = s != null,
                    viewers = s?.viewerCount ?: 0,
                    title = s?.title.orEmpty(),
                    game = s?.gameName.orEmpty(),
                )
            }
        }
    }

    suspend fun search(query: String): List<HelixChannelSearch> =
        if (query.isBlank()) emptyList() else runCatching { helix.searchChannels(query.trim()) }.getOrDefault(emptyList())

    private suspend fun saveCache() {
        val list = _info.value.values.map { it.copy(isLive = false, viewers = 0, title = "", game = "") }
        store.edit { it[INFO_CACHE] = AppJson.encodeToString(list) }
    }

    companion object {
        private const val TAG = "ChannelRepository"
        private val CHANNELS = stringPreferencesKey("channels")
        private val INFO_CACHE = stringPreferencesKey("channel_info")
        private val CUSTOM_NAMES = stringPreferencesKey("channel_names")
        private val MUTED = stringPreferencesKey("channels_muted")
        private val NO_TITLE_BAR = stringPreferencesKey("channels_no_title_bar")
        private val LAST_CHANNEL = stringPreferencesKey("last_channel")
        private val GROUPS = stringPreferencesKey("channel_groups")

        private fun split(raw: String?): List<String> = raw.orEmpty().split(',').filter { it.isNotEmpty() }

        /** A login, or the key of a combined chat with a valid id. */
        private fun isPage(page: String): Boolean =
            if (ChannelGroup.isKey(page)) ChannelGroup.isValidId(ChannelGroup.idOf(page)) else VALID.matches(page)

        private fun decodeGroups(raw: String?): List<ChannelGroup>? =
            decodeStored<List<ChannelGroup>>(raw, emptyList(), "combined chats")?.filter { ChannelGroup.isValidId(it.id) }

        private fun decodeNames(raw: String?): Map<String, String>? = decodeStored(raw, emptyMap(), "channel names")

        private val VALID = Regex("^[a-z0-9_]{1,25}$")

        fun normalize(input: String): String? {
            val login = input.trim().removePrefix("#").removePrefix("@")
                .substringAfterLast("twitch.tv/").substringBefore('/').substringBefore('?')
                .lowercase()
            return login.takeIf { VALID.matches(it) }
        }
    }
}
