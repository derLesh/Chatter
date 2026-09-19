package dev.chatter.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class Settings(
    val fontSize: Float = 14f,
    val showTimestamps: Boolean = true,
    val messageLimit: Int = 500,
    val mentionKeywords: List<String> = emptyList(),
    val animatedEmotes: Boolean = true,
    /** Names of the most recently used emotes, newest first. */
    val recentEmotes: List<String> = emptyList(),
)

class SettingsRepository(
    private val store: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    val settings: StateFlow<Settings> = store.data.map { p ->
        Settings(
            fontSize = p[FONT_SIZE] ?: 14f,
            showTimestamps = p[TIMESTAMPS] ?: true,
            messageLimit = p[LIMIT] ?: 500,
            mentionKeywords = p[KEYWORDS].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
            animatedEmotes = p[ANIMATED] ?: true,
            recentEmotes = p[RECENT_EMOTES].orEmpty().split(' ').filter { it.isNotEmpty() },
        )
    }.stateIn(scope, SharingStarted.Eagerly, Settings())

    suspend fun setFontSize(v: Float) = store.edit { it[FONT_SIZE] = v }
    suspend fun setShowTimestamps(v: Boolean) = store.edit { it[TIMESTAMPS] = v }
    suspend fun setMessageLimit(v: Int) = store.edit { it[LIMIT] = v }
    suspend fun setMentionKeywords(v: String) = store.edit { it[KEYWORDS] = v }
    suspend fun setAnimatedEmotes(v: Boolean) = store.edit { it[ANIMATED] = v }

    suspend fun addRecentEmote(name: String) = store.edit { p ->
        val list = p[RECENT_EMOTES].orEmpty().split(' ').filter { it.isNotEmpty() && it != name }
        p[RECENT_EMOTES] = (listOf(name) + list).take(MAX_RECENT).joinToString(" ")
    }

    private companion object {
        val FONT_SIZE = floatPreferencesKey("font_size")
        val TIMESTAMPS = booleanPreferencesKey("timestamps")
        val LIMIT = intPreferencesKey("message_limit")
        val KEYWORDS = stringPreferencesKey("mention_keywords")
        val ANIMATED = booleanPreferencesKey("animated_emotes")
        val RECENT_EMOTES = stringPreferencesKey("recent_emotes")
        const val MAX_RECENT = 40
    }
}
