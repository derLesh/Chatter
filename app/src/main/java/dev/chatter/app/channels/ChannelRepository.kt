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
    val info: StateFlow<Map<String, ChannelInfo>> = _info

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
        }
    }

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
        private val VALID = Regex("^[a-z0-9_]{1,25}$")

        fun normalize(input: String): String? {
            val login = input.trim().removePrefix("#").removePrefix("@")
                .substringAfterLast("twitch.tv/").substringBefore('/').substringBefore('?')
                .lowercase()
            return login.takeIf { VALID.matches(it) }
        }
    }
}
