package dev.chatter.app.chat

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chatter.app.net.AppJson
import dev.chatter.app.net.decodeStored
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Nicknames the user gave other chatters, by login and not per channel: a nickname applies wherever
 * that person chats.
 */
class NicknameRepository(private val store: DataStore<Preferences>, scope: CoroutineScope) {
    /** Nickname by lowercase login. */
    val nicknames: StateFlow<Map<String, String>> = store.data
        .map { p -> decode(p[NICKNAMES]).orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Sets a nickname; blank restores the Twitch name. */
    suspend fun set(login: String, nickname: String) {
        val key = login.lowercase()
        val chosen = nickname.trim()
        store.edit { p ->
            // Never writes over nicknames it cannot read; see decodeStored.
            val all = decode(p[NICKNAMES]) ?: return@edit
            p[NICKNAMES] = AppJson.encodeToString(if (chosen.isEmpty()) all - key else all + (key to chosen))
        }
    }

    /** Replaces all nicknames, for restoring a backup. */
    suspend fun replaceAll(all: Map<String, String>) = store.edit { p ->
        p[NICKNAMES] = AppJson.encodeToString(all.mapKeys { it.key.lowercase() })
    }

    fun nicknameOf(login: String?): String? = login?.let { nicknames.value[it.lowercase()] }

    private companion object {
        val NICKNAMES = stringPreferencesKey("chatter_nicknames")

        fun decode(raw: String?): Map<String, String>? = decodeStored(raw, emptyMap(), "nicknames")
    }
}
