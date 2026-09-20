package dev.chatter.app.channels

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chatter.app.net.AppJson
import dev.chatter.app.net.HelixApi
import dev.chatter.app.net.HelixChannelSearch
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
 * A channel reduced to what an icon or a shortcut is made of. The live status is deliberately
 * left out: it changes every two minutes, and everything built from this would be rebuilt with it.
 */
data class ChannelIdentity(val login: String, val name: String, val avatarUrl: String?)

/** The user's channel list (persisted, ordered) plus profile info and live status. */
class ChannelRepository(
    private val store: DataStore<Preferences>,
    private val helix: HelixApi,
    scope: CoroutineScope,
) {
    val channels: StateFlow<List<String>> = store.data
        .map { p -> p[CHANNELS].orEmpty().split(',').filter { it.isNotEmpty() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _info = MutableStateFlow<Map<String, ChannelInfo>>(emptyMap())

    /** Names the user gave channels themselves, by login. Empty unless one was renamed. */
    val customNames: StateFlow<Map<String, String>> = store.data
        .map { p -> decodeNames(p[CUSTOM_NAMES]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /**
     * Profile info with the user's own names already applied, so every screen showing a channel
     * picks them up without knowing they exist.
     */
    val info: StateFlow<Map<String, ChannelInfo>> = combine(_info, customNames) { info, names ->
        if (names.isEmpty()) info
        else info.mapValues { (login, i) -> names[login]?.let { i.copy(displayName = it) } ?: i }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /**
     * The channels in list order, with the name and picture they are shown under. What Android
     * needs to hear about (shortcuts, notification channels) is built from this, so it is only
     * told when something it can see has actually changed.
     */
    val identities: StateFlow<List<ChannelIdentity>> = combine(channels, info) { list, info ->
        list.map { login ->
            val i = info[login]
            ChannelIdentity(login, i?.displayName ?: login, i?.avatarUrl)
        }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The channel the user was last reading. Kept on disk because the chat screen cannot work it
     * out on its own after Android has stopped the process.
     */
    val lastChannel: StateFlow<String?> = store.data
        .map { p -> p[LAST_CHANNEL] }
        .stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun setLastChannel(login: String) = store.edit { it[LAST_CHANNEL] = login }

    /** Channels the user switched notifications off for. Absent means notifications are on. */
    val mutedChannels: StateFlow<Set<String>> = store.data
        .map { p -> p[MUTED].orEmpty().split(',').filter { it.isNotEmpty() }.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Channels kept out of the unread strip in the title bar. Absent means they show up. */
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

    /** Loads cached profile info so avatars show instantly on start. */
    suspend fun loadCache() {
        val raw = store.data.first()[INFO_CACHE] ?: return
        runCatching { AppJson.decodeFromString<List<ChannelInfo>>(raw) }
            .onSuccess { list -> _info.update { current -> list.associateBy { it.login } + current } }
    }

    suspend fun currentChannels(): List<String> = store.data.first()[CHANNELS].orEmpty().split(',').filter { it.isNotEmpty() }

    /** Returns the normalized login, or null if the input is not a valid channel name. */
    suspend fun add(input: String): String? {
        val login = normalize(input) ?: return null
        store.edit { p ->
            val list = p[CHANNELS].orEmpty().split(',').filter { it.isNotEmpty() }
            if (login !in list) p[CHANNELS] = (list + login).joinToString(",")
        }
        refreshUsers(listOf(login))
        return login
    }

    suspend fun remove(login: String) {
        store.edit { p ->
            p[CHANNELS] = p[CHANNELS].orEmpty().split(',').filter { it.isNotEmpty() && it != login }.joinToString(",")
            val names = decodeNames(p[CUSTOM_NAMES])
            if (login in names) p[CUSTOM_NAMES] = AppJson.encodeToString(names - login)
            p[MUTED] = p[MUTED].orEmpty().split(',').filter { it.isNotEmpty() && it != login }.joinToString(",")
            p[NO_TITLE_BAR] = p[NO_TITLE_BAR].orEmpty().split(',').filter { it.isNotEmpty() && it != login }.joinToString(",")
        }
    }

    /** Gives a channel a name of the user's choosing; a blank name restores the Twitch one. */
    suspend fun rename(login: String, name: String) {
        val chosen = name.trim()
        store.edit { p ->
            val names = decodeNames(p[CUSTOM_NAMES])
            p[CUSTOM_NAMES] = AppJson.encodeToString(if (chosen.isEmpty()) names - login else names + (login to chosen))
        }
    }

    /** The name Twitch reports, ignoring any renaming, for showing what a reset would restore. */
    fun twitchName(login: String): String = _info.value[login]?.displayName ?: login

    suspend fun move(login: String, delta: Int) {
        store.edit { p ->
            val list = p[CHANNELS].orEmpty().split(',').filter { it.isNotEmpty() }.toMutableList()
            val from = list.indexOf(login)
            val to = (from + delta).coerceIn(0, list.lastIndex)
            if (from >= 0 && from != to) {
                list.add(to, list.removeAt(from))
                p[CHANNELS] = list.joinToString(",")
            }
        }
    }

    /**
     * Replaces the whole channel list and everything the user set on it, for restoring a backup.
     * Names that are not valid Twitch logins are dropped rather than joined and rejected later.
     */
    suspend fun restore(
        logins: List<String>,
        names: Map<String, String>,
        notificationsOff: Set<String>,
        hiddenUnread: Set<String>,
    ) {
        val valid = logins.mapNotNull { normalize(it) }.distinct()
        store.edit { p ->
            p[CHANNELS] = valid.joinToString(",")
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

    /** One batched Helix call for all channels. Only called while the app is in the foreground. */
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

        private fun decodeNames(raw: String?): Map<String, String> =
            raw?.let { runCatching { AppJson.decodeFromString<Map<String, String>>(it) }.getOrNull() }.orEmpty()
        private val VALID = Regex("^[a-z0-9_]{1,25}$")

        fun normalize(input: String): String? {
            val login = input.trim().removePrefix("#").removePrefix("@")
                .substringAfterLast("twitch.tv/").substringBefore('/').substringBefore('?')
                .lowercase()
            return login.takeIf { VALID.matches(it) }
        }
    }
}
