package dev.chatter.app.chat

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chatter.app.net.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Names the user gave other chatters. They are keyed by login and deliberately not by channel:
 * someone you know as "Bot" is that person in every channel they turn up in.
 */
class NicknameRepository(private val store: DataStore<Preferences>, scope: CoroutineScope) {
    /** Nickname by lowercase login. Empty unless the user renamed somebody. */
    val nicknames: StateFlow<Map<String, String>> = store.data
        .map { p -> decode(p[NICKNAMES]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Gives a chatter a nickname; a blank one restores the name Twitch reports. */
    suspend fun set(login: String, nickname: String) {
        val key = login.lowercase()
        val chosen = nickname.trim()
        store.edit { p ->
            val all = decode(p[NICKNAMES])
            p[NICKNAMES] = AppJson.encodeToString(if (chosen.isEmpty()) all - key else all + (key to chosen))
        }
    }

    /** Replaces every nickname, for restoring a backup. */
    suspend fun replaceAll(all: Map<String, String>) = store.edit { p ->
        p[NICKNAMES] = AppJson.encodeToString(all.mapKeys { it.key.lowercase() })
    }

    fun nicknameOf(login: String?): String? = login?.let { nicknames.value[it.lowercase()] }

    private companion object {
        val NICKNAMES = stringPreferencesKey("chatter_nicknames")

        fun decode(raw: String?): Map<String, String> =
            raw?.let { runCatching { AppJson.decodeFromString<Map<String, String>>(it) }.getOrNull() }.orEmpty()
    }
}
