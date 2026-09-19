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

enum class ThemeMode { System, Light, Dark }

data class Settings(
    val fontSize: Float = 14f,
    val showTimestamps: Boolean = true,
    val messageLimit: Int = 500,
    val mentionKeywords: List<String> = emptyList(),
    val animatedEmotes: Boolean = true,
    /** Names of the most recently used emotes, newest first. */
    val recentEmotes: List<String> = emptyList(),
    val themeMode: ThemeMode = ThemeMode.System,
    /** Material You: colors derived from the wallpaper instead of Twitch purple. */
    val dynamicColor: Boolean = true,
    /** Alternate the background of every other message for easier reading. */
    val alternateBackground: Boolean = false,
    /** Mention highlight: [HIGHLIGHT_DEFAULT] (red), [HIGHLIGHT_ACCENT] (theme color) or an ARGB color. */
    val highlightColor: Int = HIGHLIGHT_DEFAULT,
    /** Load recent messages from the recent-messages service when joining a channel. */
    val loadHistory: Boolean = true,
    /** Animate new messages into view instead of jumping. */
    val smoothScrolling: Boolean = true,
    val emotesEnabled: Boolean = true,
    /** Draw zero-width emotes on top of the previous emote (off: show them next to it). */
    val zeroWidthEmotes: Boolean = true,
    val showUnlisted7tv: Boolean = false,
    /** Live 7TV emote changes (added / removed / renamed) as notices in the chat. */
    val sevenTvEvents: Boolean = true,
    /** Show the avatars of channels with unread messages in the title bar. */
    val unreadInTitleBar: Boolean = true,
    /** Suggest emotes while typing. */
    val emoteSuggestions: Boolean = true,
    /** Suggest the names of recent chatters after an "@". */
    val userSuggestions: Boolean = true,
) {
    companion object {
        // Real ARGB colors are always opaque (0xFF......), so these can never clash with one.
        const val HIGHLIGHT_DEFAULT = 0
        const val HIGHLIGHT_ACCENT = 1
    }
}

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
            themeMode = p[THEME_MODE]?.let { v -> ThemeMode.entries.firstOrNull { it.name == v } } ?: ThemeMode.System,
            dynamicColor = p[DYNAMIC_COLOR] ?: true,
            alternateBackground = p[ALTERNATE_BG] ?: false,
            highlightColor = p[HIGHLIGHT_COLOR] ?: Settings.HIGHLIGHT_DEFAULT,
            loadHistory = p[LOAD_HISTORY] ?: true,
            smoothScrolling = p[SMOOTH_SCROLLING] ?: true,
            emotesEnabled = p[EMOTES_ENABLED] ?: true,
            zeroWidthEmotes = p[ZERO_WIDTH] ?: true,
            showUnlisted7tv = p[UNLISTED_7TV] ?: false,
            sevenTvEvents = p[SEVENTV_EVENTS] ?: true,
            unreadInTitleBar = p[UNREAD_TITLE_BAR] ?: true,
            emoteSuggestions = p[EMOTE_SUGGESTIONS] ?: true,
            userSuggestions = p[USER_SUGGESTIONS] ?: true,
        )
    }.stateIn(scope, SharingStarted.Eagerly, Settings())

    suspend fun setFontSize(v: Float) = store.edit { it[FONT_SIZE] = v }
    suspend fun setShowTimestamps(v: Boolean) = store.edit { it[TIMESTAMPS] = v }
    suspend fun setMessageLimit(v: Int) = store.edit { it[LIMIT] = v }
    suspend fun setMentionKeywords(v: String) = store.edit { it[KEYWORDS] = v }
    suspend fun setAnimatedEmotes(v: Boolean) = store.edit { it[ANIMATED] = v }
    suspend fun setThemeMode(v: ThemeMode) = store.edit { it[THEME_MODE] = v.name }
    suspend fun setDynamicColor(v: Boolean) = store.edit { it[DYNAMIC_COLOR] = v }
    suspend fun setAlternateBackground(v: Boolean) = store.edit { it[ALTERNATE_BG] = v }
    suspend fun setHighlightColor(v: Int) = store.edit { it[HIGHLIGHT_COLOR] = v }
    suspend fun setLoadHistory(v: Boolean) = store.edit { it[LOAD_HISTORY] = v }
    suspend fun setSmoothScrolling(v: Boolean) = store.edit { it[SMOOTH_SCROLLING] = v }
    suspend fun setEmotesEnabled(v: Boolean) = store.edit { it[EMOTES_ENABLED] = v }
    suspend fun setZeroWidthEmotes(v: Boolean) = store.edit { it[ZERO_WIDTH] = v }
    suspend fun setShowUnlisted7tv(v: Boolean) = store.edit { it[UNLISTED_7TV] = v }
    suspend fun setSevenTvEvents(v: Boolean) = store.edit { it[SEVENTV_EVENTS] = v }
    suspend fun setUnreadInTitleBar(v: Boolean) = store.edit { it[UNREAD_TITLE_BAR] = v }
    suspend fun setEmoteSuggestions(v: Boolean) = store.edit { it[EMOTE_SUGGESTIONS] = v }
    suspend fun setUserSuggestions(v: Boolean) = store.edit { it[USER_SUGGESTIONS] = v }

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
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val ALTERNATE_BG = booleanPreferencesKey("alternate_background")
        val HIGHLIGHT_COLOR = intPreferencesKey("highlight_color")
        val LOAD_HISTORY = booleanPreferencesKey("load_history")
        val SMOOTH_SCROLLING = booleanPreferencesKey("smooth_scrolling")
        val EMOTES_ENABLED = booleanPreferencesKey("emotes_enabled")
        val ZERO_WIDTH = booleanPreferencesKey("zero_width_emotes")
        val UNLISTED_7TV = booleanPreferencesKey("show_unlisted_7tv")
        val SEVENTV_EVENTS = booleanPreferencesKey("seventv_events")
        val UNREAD_TITLE_BAR = booleanPreferencesKey("unread_title_bar")
        val EMOTE_SUGGESTIONS = booleanPreferencesKey("emote_suggestions")
        val USER_SUGGESTIONS = booleanPreferencesKey("user_suggestions")
        const val MAX_RECENT = 40
    }
}
