package me.lesh.chatter.chat

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import me.lesh.chatter.net.AppJson
import me.lesh.chatter.net.decodeStored
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Private notes the user keeps on other chatters, by login and not per channel. They never leave
 * the phone except in a backup file the user saves.
 */
class NoteRepository(private val store: DataStore<Preferences>, scope: CoroutineScope) {
    /** Note by lowercase login. */
    val notes: StateFlow<Map<String, String>> = store.data
        .map { p -> decode(p[NOTES]).orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Sets a note; blank removes it. */
    suspend fun set(login: String, note: String) {
        val key = login.lowercase()
        val written = note.trim().take(MAX_LENGTH)
        store.edit { p ->
            // Never writes over notes it cannot read; see decodeStored.
            val all = decode(p[NOTES]) ?: return@edit
            p[NOTES] = AppJson.encodeToString(if (written.isEmpty()) all - key else all + (key to written))
        }
    }

    /** Replaces all notes, for restoring a backup. */
    suspend fun replaceAll(all: Map<String, String>) = store.edit { p ->
        p[NOTES] = AppJson.encodeToString(all.mapKeys { it.key.lowercase() })
    }

    companion object {
        const val MAX_LENGTH = 500

        private val NOTES = stringPreferencesKey("chatter_notes")

        private fun decode(raw: String?): Map<String, String>? = decodeStored(raw, emptyMap(), "notes")
    }
}
