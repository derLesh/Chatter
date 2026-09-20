package dev.chatter.app.settings

import androidx.datastore.core.DataStore
import dev.chatter.app.badges.BadgeProvider
import dev.chatter.app.emotes.EmoteProvider
import dev.chatter.app.ui.theme.NameColorPalette
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable

enum class ThemeMode { System, Light, Dark }

/** How the time in front of a message is written, or [Off] for no timestamp at all. */
enum class TimestampFormat(val pattern: String?) {
    Off(null),
    Short("HH:mm"),
    Seconds("HH:mm:ss"),
    Twelve("h:mm a"),
}

@Serializable
data class Settings(
    val fontSize: Float = 14f,
    val timestamps: TimestampFormat = TimestampFormat.Short,
    val messageLimit: Int = 500,
    val mentionKeywords: List<String> = emptyList(),
    /** Messages containing one of these words are never shown. */
    val muteKeywords: List<String> = emptyList(),
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
    /** Put an "@" in front of a name picked from the suggestions. */
    val mentionWithAt: Boolean = true,
    /** Keep deleted messages in the chat, struck through, instead of hiding them. */
    val showDeleted: Boolean = true,
    /** The badge providers whose badges are shown in front of a name. */
    val badgeProviders: Set<BadgeProvider> = BadgeProvider.entries.toSet(),
    /** The emote providers whose emotes are shown; the others stay plain text. */
    val emoteProviders: Set<EmoteProvider> = EmoteProvider.entries.toSet(),
    /** Keep the screen awake while the chat is on screen. */
    val keepScreenOn: Boolean = false,
    /** Offer mention notifications as a floating chat bubble over other apps. */
    val bubbles: Boolean = false,
    /** Mark the first message a chatter ever writes in a channel (Twitch's own flag). */
    val highlightFirstMessages: Boolean = true,
    /** How the name colors users picked are made readable on the chat background. */
    val nameColors: NameColorPalette = NameColorPalette.HslLuma,
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
            // Falls back to the old on/off switch so an existing choice survives the update.
            timestamps = p[TIMESTAMP_FORMAT]?.let { v -> TimestampFormat.entries.firstOrNull { it.name == v } }
                ?: if (p[TIMESTAMPS] == false) TimestampFormat.Off else TimestampFormat.Short,
            messageLimit = p[LIMIT] ?: 500,
            mentionKeywords = p[KEYWORDS].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
            muteKeywords = p[MUTE_KEYWORDS].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
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
            mentionWithAt = p[MENTION_WITH_AT] ?: true,
            showDeleted = p[SHOW_DELETED] ?: true,
            keepScreenOn = p[KEEP_SCREEN_ON] ?: false,
            bubbles = p[BUBBLES] ?: false,
            highlightFirstMessages = p[FIRST_MESSAGES] ?: true,
            nameColors = p[NAME_COLORS]?.let { v -> NameColorPalette.entries.firstOrNull { it.name == v } }
                ?: NameColorPalette.HslLuma,
            badgeProviders = p[BADGE_PROVIDERS]
                ?.split(',')?.mapNotNull { v -> BadgeProvider.entries.firstOrNull { it.name == v } }?.toSet()
                ?: BadgeProvider.entries.toSet(),
            emoteProviders = p[EMOTE_PROVIDERS]
                ?.split(',')?.mapNotNull { v -> EmoteProvider.entries.firstOrNull { it.name == v } }?.toSet()
                ?: EmoteProvider.entries.toSet(),
        )
    }.stateIn(scope, SharingStarted.Eagerly, Settings())

    suspend fun setFontSize(v: Float) = store.edit { it[FONT_SIZE] = v }
    suspend fun setTimestamps(v: TimestampFormat) = store.edit { it[TIMESTAMP_FORMAT] = v.name }
    suspend fun setMessageLimit(v: Int) = store.edit { it[LIMIT] = v }
    suspend fun setMentionKeywords(v: String) = store.edit { it[KEYWORDS] = v }
    suspend fun setMuteKeywords(v: String) = store.edit { it[MUTE_KEYWORDS] = v }
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
    suspend fun setMentionWithAt(v: Boolean) = store.edit { it[MENTION_WITH_AT] = v }
    suspend fun setShowDeleted(v: Boolean) = store.edit { it[SHOW_DELETED] = v }
    suspend fun setKeepScreenOn(v: Boolean) = store.edit { it[KEEP_SCREEN_ON] = v }
    suspend fun setBubbles(v: Boolean) = store.edit { it[BUBBLES] = v }
    suspend fun setHighlightFirstMessages(v: Boolean) = store.edit { it[FIRST_MESSAGES] = v }
    suspend fun setNameColors(v: NameColorPalette) = store.edit { it[NAME_COLORS] = v.name }
    suspend fun setBadgeProviders(v: Set<BadgeProvider>) = store.edit { p -> p[BADGE_PROVIDERS] = v.joinToString(",") { it.name } }
    suspend fun setEmoteProviders(v: Set<EmoteProvider>) = store.edit { p -> p[EMOTE_PROVIDERS] = v.joinToString(",") { it.name } }

    /**
     * The version whose changelog the user has read, which is what the app compares against to
     * find out whether it has anything new to tell them. Null until they have read one.
     */
    val seenVersion: Flow<String?> = store.data.map { it[SEEN_VERSION] }

    suspend fun setSeenVersion(v: String) = store.edit { it[SEEN_VERSION] = v }

    /**
     * Writes every setting at once, for restoring a backup. New settings have to be added here
     * too, or a restore would quietly leave them at whatever they were.
     */
    suspend fun replaceAll(s: Settings) = store.edit { p ->
        p[FONT_SIZE] = s.fontSize
        p[TIMESTAMP_FORMAT] = s.timestamps.name
        p[LIMIT] = s.messageLimit
        p[KEYWORDS] = s.mentionKeywords.joinToString(",")
        p[MUTE_KEYWORDS] = s.muteKeywords.joinToString(",")
        p[ANIMATED] = s.animatedEmotes
        p[RECENT_EMOTES] = s.recentEmotes.joinToString(" ")
        p[THEME_MODE] = s.themeMode.name
        p[DYNAMIC_COLOR] = s.dynamicColor
        p[ALTERNATE_BG] = s.alternateBackground
        p[HIGHLIGHT_COLOR] = s.highlightColor
        p[LOAD_HISTORY] = s.loadHistory
        p[SMOOTH_SCROLLING] = s.smoothScrolling
        p[EMOTES_ENABLED] = s.emotesEnabled
        p[ZERO_WIDTH] = s.zeroWidthEmotes
        p[UNLISTED_7TV] = s.showUnlisted7tv
        p[SEVENTV_EVENTS] = s.sevenTvEvents
        p[UNREAD_TITLE_BAR] = s.unreadInTitleBar
        p[EMOTE_SUGGESTIONS] = s.emoteSuggestions
        p[USER_SUGGESTIONS] = s.userSuggestions
        p[MENTION_WITH_AT] = s.mentionWithAt
        p[SHOW_DELETED] = s.showDeleted
        p[KEEP_SCREEN_ON] = s.keepScreenOn
        p[BUBBLES] = s.bubbles
        p[FIRST_MESSAGES] = s.highlightFirstMessages
        p[NAME_COLORS] = s.nameColors.name
        p[BADGE_PROVIDERS] = s.badgeProviders.joinToString(",") { it.name }
        p[EMOTE_PROVIDERS] = s.emoteProviders.joinToString(",") { it.name }
    }

    suspend fun addRecentEmote(name: String) = store.edit { p ->
        val list = p[RECENT_EMOTES].orEmpty().split(' ').filter { it.isNotEmpty() && it != name }
        p[RECENT_EMOTES] = (listOf(name) + list).take(MAX_RECENT).joinToString(" ")
    }

    private companion object {
        val FONT_SIZE = floatPreferencesKey("font_size")
        val TIMESTAMPS = booleanPreferencesKey("timestamps")
        val TIMESTAMP_FORMAT = stringPreferencesKey("timestamp_format")
        val LIMIT = intPreferencesKey("message_limit")
        val KEYWORDS = stringPreferencesKey("mention_keywords")
        val MUTE_KEYWORDS = stringPreferencesKey("mute_keywords")
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
        val MENTION_WITH_AT = booleanPreferencesKey("mention_with_at")
        val SHOW_DELETED = booleanPreferencesKey("show_deleted")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val BUBBLES = booleanPreferencesKey("chat_bubbles")
        val EMOTE_PROVIDERS = stringPreferencesKey("emote_providers")
        val BADGE_PROVIDERS = stringPreferencesKey("badge_providers")
        val NAME_COLORS = stringPreferencesKey("name_colors")
        val FIRST_MESSAGES = booleanPreferencesKey("highlight_first_messages")
        val SEEN_VERSION = stringPreferencesKey("seen_changelog_version")
        const val MAX_RECENT = 40
    }
}
