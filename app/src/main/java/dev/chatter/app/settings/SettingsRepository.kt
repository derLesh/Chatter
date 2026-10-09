package dev.chatter.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chatter.app.badges.BadgeProvider
import dev.chatter.app.chat.ImageLinks
import dev.chatter.app.emotes.EmoteProvider
import dev.chatter.app.ui.theme.NameColorPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable

enum class ThemeMode { System, Light, Dark }

/** The time format in front of a message, or [Off]. */
enum class TimestampFormat(val pattern: String?) {
    Off(null),
    Short("HH:mm"),
    Seconds("HH:mm:ss"),
    Twelve("h:mm a"),
}

/**
 * Behaviour on metered networks. [SaveData] does for Chatter what Android's Data Saver does for the
 * whole phone.
 */
enum class MobileData { Normal, SaveData }

/** What tapping a message or its name does. Holding always opens the user card. */
enum class TapAction { Reply, UserCard, Mention, Nothing }

@Serializable
data class Settings(
    val fontSize: Float = 14f,
    val timestamps: TimestampFormat = TimestampFormat.Short,
    val messageLimit: Int = 500,
    val mentionKeywords: List<String> = emptyList(),
    /** Messages containing one of these words are hidden. */
    val muteKeywords: List<String> = emptyList(),
    val animatedEmotes: Boolean = true,
    /** Fewer frames per second for animated emotes once the chat has been idle for a while. */
    val slowIdleEmotes: Boolean = true,
    /** Names of the most recently used emotes, newest first. */
    val recentEmotes: List<String> = emptyList(),
    val themeMode: ThemeMode = ThemeMode.System,
    /** Material You colors from the wallpaper instead of Twitch purple. */
    val dynamicColor: Boolean = true,
    /** Black instead of dark grey behind the chat, for OLED screens. Dark theme only. */
    val pureBlack: Boolean = false,
    /** Different background on every other message. */
    val alternateBackground: Boolean = false,
    /** Mention highlight: [HIGHLIGHT_DEFAULT] (red), [HIGHLIGHT_ACCENT] (theme color) or an ARGB color. */
    val highlightColor: Int = HIGHLIGHT_DEFAULT,
    /** Load recent messages from the recent-messages service when joining a channel. */
    val loadHistory: Boolean = true,
    /** Animate new messages into view instead of jumping. */
    val smoothScrolling: Boolean = true,
    val emotesEnabled: Boolean = true,
    /** Draw zero-width emotes on top of the previous emote; off shows them next to it. */
    val zeroWidthEmotes: Boolean = true,
    val showUnlisted7tv: Boolean = false,
    /** Live 7TV emote changes as notices in the chat. */
    val sevenTvEvents: Boolean = true,
    /** Names drawn with the gradient or picture their owner picked on 7TV. */
    val sevenTvPaints: Boolean = true,
    /** Avatars of channels with unread messages in the title bar. */
    val unreadInTitleBar: Boolean = true,
    /** Every channel as a tab in the title bar instead of a menu. */
    val channelTabs: Boolean = true,
    /** Suggest emotes while typing. */
    val emoteSuggestions: Boolean = true,
    /** Suggest recent chatters after an "@". */
    val userSuggestions: Boolean = true,
    /** Put an "@" in front of a suggested name. */
    val mentionWithAt: Boolean = true,
    /** Show deleted messages struck through instead of hiding them. */
    val showDeleted: Boolean = true,
    /** Check GitHub daily for a newer version; only in the GitHub APK. */
    val updateCheck: Boolean = true,
    /** Show linked images instead of their URL. */
    val inlineImages: Boolean = true,
    /** The only hosts linked images are loaded from. */
    val imageHosts: List<String> = ImageLinks.DEFAULT_HOSTS,
    /** Write links in full instead of shortening them. */
    val fullLinks: Boolean = false,
    /** Leave out linked images, animation and sharp emotes on metered networks. */
    val mobileData: MobileData = MobileData.Normal,
    /** Swiping past the last channel wraps around to the first. */
    val carouselChannels: Boolean = false,
    /** Badge providers shown in front of names. */
    val badgeProviders: Set<BadgeProvider> = BadgeProvider.entries.toSet(),
    /** Emote providers shown; the others stay text. */
    val emoteProviders: Set<EmoteProvider> = EmoteProvider.entries.toSet(),
    /**
     * Short vibrations for things the user did not trigger: a mention arriving on screen, a held
     * message, a send that failed.
     */
    val haptics: Boolean = true,
    /** Keep the screen awake while the chat is on screen. */
    val keepScreenOn: Boolean = false,
    /** Offer mention notifications as a floating chat bubble over other apps. */
    val bubbles: Boolean = false,
    /** The sender's Twitch avatar in notifications. */
    val senderAvatars: Boolean = true,
    /** Mark a chatter's first message in a channel (Twitch's flag). */
    val highlightFirstMessages: Boolean = true,
    /** How user-picked name colors are adjusted for readability. */
    val nameColors: NameColorPalette = NameColorPalette.HslLuma,
    /** Tap on a message. */
    val messageTap: TapAction = TapAction.Reply,
    /** Tap on the name in front of a message. */
    val nameTap: TapAction = TapAction.UserCard,
    /**
     * Copy instead of Reply as the first action on the user card, for people who reply by tapping.
     */
    val copyFirst: Boolean = false,
) {
    companion object {
        // ARGB colors are opaque (0xFF......), so these never clash with one.
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
            // Falls back to the old on/off switch.
            timestamps = p[TIMESTAMP_FORMAT]?.let { v -> TimestampFormat.entries.firstOrNull { it.name == v } }
                ?: if (p[TIMESTAMPS] == false) TimestampFormat.Off else TimestampFormat.Short,
            messageLimit = p[LIMIT] ?: 500,
            mentionKeywords = words(p[KEYWORDS]),
            muteKeywords = words(p[MUTE_KEYWORDS]),
            animatedEmotes = p[ANIMATED] ?: true,
            slowIdleEmotes = p[SLOW_IDLE_EMOTES] ?: true,
            recentEmotes = p[RECENT_EMOTES].orEmpty().split(' ').filter { it.isNotEmpty() },
            themeMode = p[THEME_MODE]?.let { v -> ThemeMode.entries.firstOrNull { it.name == v } } ?: ThemeMode.System,
            dynamicColor = p[DYNAMIC_COLOR] ?: true,
            pureBlack = p[PURE_BLACK] ?: false,
            alternateBackground = p[ALTERNATE_BG] ?: false,
            highlightColor = p[HIGHLIGHT_COLOR] ?: Settings.HIGHLIGHT_DEFAULT,
            loadHistory = p[LOAD_HISTORY] ?: true,
            smoothScrolling = p[SMOOTH_SCROLLING] ?: true,
            emotesEnabled = p[EMOTES_ENABLED] ?: true,
            zeroWidthEmotes = p[ZERO_WIDTH] ?: true,
            showUnlisted7tv = p[UNLISTED_7TV] ?: false,
            sevenTvEvents = p[SEVENTV_EVENTS] ?: true,
            sevenTvPaints = p[SEVENTV_PAINTS] ?: true,
            unreadInTitleBar = p[UNREAD_TITLE_BAR] ?: true,
            channelTabs = p[CHANNEL_TABS] ?: true,
            emoteSuggestions = p[EMOTE_SUGGESTIONS] ?: true,
            userSuggestions = p[USER_SUGGESTIONS] ?: true,
            mentionWithAt = p[MENTION_WITH_AT] ?: true,
            showDeleted = p[SHOW_DELETED] ?: true,
            updateCheck = p[UPDATE_CHECK] ?: true,
            inlineImages = p[INLINE_IMAGES] ?: true,
            imageHosts = hosts(p[IMAGE_HOSTS]),
            fullLinks = p[FULL_LINKS] ?: false,
            mobileData = p[MOBILE_DATA]?.let { v -> MobileData.entries.firstOrNull { it.name == v } } ?: MobileData.Normal,
            carouselChannels = p[CAROUSEL_CHANNELS] ?: false,
            haptics = p[HAPTICS] ?: true,
            keepScreenOn = p[KEEP_SCREEN_ON] ?: false,
            bubbles = p[BUBBLES] ?: false,
            senderAvatars = p[SENDER_AVATARS] ?: true,
            highlightFirstMessages = p[FIRST_MESSAGES] ?: true,
            nameColors = p[NAME_COLORS]?.let { v -> NameColorPalette.entries.firstOrNull { it.name == v } }
                ?: NameColorPalette.HslLuma,
            messageTap = p[MESSAGE_TAP]?.let { v -> TapAction.entries.firstOrNull { it.name == v } } ?: TapAction.Reply,
            copyFirst = p[COPY_FIRST] ?: false,
            nameTap = p[NAME_TAP]?.let { v -> TapAction.entries.firstOrNull { it.name == v } } ?: TapAction.UserCard,
            badgeProviders = badgeProviders(p[BADGE_PROVIDERS]),
            emoteProviders = emoteProviders(p[EMOTE_PROVIDERS]),
        )
    }.stateIn(scope, SharingStarted.Eagerly, Settings())

    suspend fun setFontSize(v: Float) = store.edit { it[FONT_SIZE] = v }
    suspend fun setTimestamps(v: TimestampFormat) = store.edit { it[TIMESTAMP_FORMAT] = v.name }
    suspend fun setMessageTap(v: TapAction) = store.edit { it[MESSAGE_TAP] = v.name }
    suspend fun setNameTap(v: TapAction) = store.edit { it[NAME_TAP] = v.name }
    suspend fun setCopyFirst(v: Boolean) = store.edit { it[COPY_FIRST] = v }
    suspend fun setMessageLimit(v: Int) = store.edit { it[LIMIT] = v }
    // List settings are changed from the stored value inside one edit, so quick successive changes
    // cannot undo each other. Stored comma-separated, as they always were.
    suspend fun updateMentionKeywords(change: (List<String>) -> List<String>) =
        store.edit { it[KEYWORDS] = change(words(it[KEYWORDS])).joinToString(",") }
    suspend fun updateMuteKeywords(change: (List<String>) -> List<String>) =
        store.edit { it[MUTE_KEYWORDS] = change(words(it[MUTE_KEYWORDS])).joinToString(",") }
    suspend fun setAnimatedEmotes(v: Boolean) = store.edit { it[ANIMATED] = v }
    suspend fun setSlowIdleEmotes(v: Boolean) = store.edit { it[SLOW_IDLE_EMOTES] = v }
    suspend fun setThemeMode(v: ThemeMode) = store.edit { it[THEME_MODE] = v.name }
    suspend fun setDynamicColor(v: Boolean) = store.edit { it[DYNAMIC_COLOR] = v }
    suspend fun setPureBlack(v: Boolean) = store.edit { it[PURE_BLACK] = v }
    suspend fun setAlternateBackground(v: Boolean) = store.edit { it[ALTERNATE_BG] = v }
    suspend fun setHighlightColor(v: Int) = store.edit { it[HIGHLIGHT_COLOR] = v }
    suspend fun setLoadHistory(v: Boolean) = store.edit { it[LOAD_HISTORY] = v }
    suspend fun setSmoothScrolling(v: Boolean) = store.edit { it[SMOOTH_SCROLLING] = v }
    suspend fun setEmotesEnabled(v: Boolean) = store.edit { it[EMOTES_ENABLED] = v }
    suspend fun setZeroWidthEmotes(v: Boolean) = store.edit { it[ZERO_WIDTH] = v }
    suspend fun setShowUnlisted7tv(v: Boolean) = store.edit { it[UNLISTED_7TV] = v }
    suspend fun setSevenTvEvents(v: Boolean) = store.edit { it[SEVENTV_EVENTS] = v }
    suspend fun setSevenTvPaints(v: Boolean) = store.edit { it[SEVENTV_PAINTS] = v }
    suspend fun setUnreadInTitleBar(v: Boolean) = store.edit { it[UNREAD_TITLE_BAR] = v }
    suspend fun setChannelTabs(v: Boolean) = store.edit { it[CHANNEL_TABS] = v }
    suspend fun setEmoteSuggestions(v: Boolean) = store.edit { it[EMOTE_SUGGESTIONS] = v }
    suspend fun setUserSuggestions(v: Boolean) = store.edit { it[USER_SUGGESTIONS] = v }
    suspend fun setMentionWithAt(v: Boolean) = store.edit { it[MENTION_WITH_AT] = v }
    suspend fun setShowDeleted(v: Boolean) = store.edit { it[SHOW_DELETED] = v }
    suspend fun setUpdateCheck(v: Boolean) = store.edit { it[UPDATE_CHECK] = v }
    suspend fun setInlineImages(v: Boolean) = store.edit { it[INLINE_IMAGES] = v }
    suspend fun updateImageHosts(change: (List<String>) -> List<String>) =
        store.edit { it[IMAGE_HOSTS] = change(hosts(it[IMAGE_HOSTS])).joinToString(",") }
    suspend fun setFullLinks(v: Boolean) = store.edit { it[FULL_LINKS] = v }
    suspend fun setMobileData(v: MobileData) = store.edit { it[MOBILE_DATA] = v.name }
    suspend fun setCarouselChannels(v: Boolean) = store.edit { it[CAROUSEL_CHANNELS] = v }
    suspend fun setHaptics(v: Boolean) = store.edit { it[HAPTICS] = v }
    suspend fun setKeepScreenOn(v: Boolean) = store.edit { it[KEEP_SCREEN_ON] = v }
    suspend fun setBubbles(v: Boolean) = store.edit { it[BUBBLES] = v }
    suspend fun setSenderAvatars(v: Boolean) = store.edit { it[SENDER_AVATARS] = v }
    suspend fun setHighlightFirstMessages(v: Boolean) = store.edit { it[FIRST_MESSAGES] = v }
    suspend fun setNameColors(v: NameColorPalette) = store.edit { it[NAME_COLORS] = v.name }
    suspend fun updateBadgeProviders(change: (Set<BadgeProvider>) -> Set<BadgeProvider>) =
        store.edit { p -> p[BADGE_PROVIDERS] = change(badgeProviders(p[BADGE_PROVIDERS])).joinToString(",") { it.name } }
    suspend fun updateEmoteProviders(change: (Set<EmoteProvider>) -> Set<EmoteProvider>) =
        store.edit { p -> p[EMOTE_PROVIDERS] = change(emoteProviders(p[EMOTE_PROVIDERS])).joinToString(",") { it.name } }

    /** The version whose changelog the user has read. Null until one was read. */
    val seenVersion: Flow<String?> = store.data.map { it[SEEN_VERSION] }

    suspend fun setSeenVersion(v: String) = store.edit { it[SEEN_VERSION] = v }

    /** Whether the screen explaining notifications was shown after the first login. */
    val notificationIntroSeen: Flow<Boolean> = store.data.map { it[NOTIFICATION_INTRO_SEEN] ?: false }

    suspend fun setNotificationIntroSeen() = store.edit { it[NOTIFICATION_INTRO_SEEN] = true }

    /**
     * The release GitHub reported at the last check (JSON) and when that was. Not part of
     * [Settings]: it is not a user choice and must not travel with a backup.
     */
    val availableUpdate: Flow<String?> = store.data.map { it[AVAILABLE_UPDATE] }
    val updateCheckedAt: Flow<Long?> = store.data.map { it[UPDATE_CHECKED_AT] }

    suspend fun setAvailableUpdate(json: String, checkedAt: Long) = store.edit {
        it[AVAILABLE_UPDATE] = json
        it[UPDATE_CHECKED_AT] = checkedAt
    }

    /**
     * Writes every setting, for restoring a backup. New settings must be added here, or a restore
     * leaves them unchanged.
     */
    suspend fun replaceAll(s: Settings) = store.edit { p ->
        p[FONT_SIZE] = s.fontSize
        p[TIMESTAMP_FORMAT] = s.timestamps.name
        p[LIMIT] = s.messageLimit
        p[KEYWORDS] = s.mentionKeywords.joinToString(",")
        p[MUTE_KEYWORDS] = s.muteKeywords.joinToString(",")
        p[ANIMATED] = s.animatedEmotes
        p[SLOW_IDLE_EMOTES] = s.slowIdleEmotes
        p[RECENT_EMOTES] = s.recentEmotes.joinToString(" ")
        p[THEME_MODE] = s.themeMode.name
        p[DYNAMIC_COLOR] = s.dynamicColor
        p[PURE_BLACK] = s.pureBlack
        p[ALTERNATE_BG] = s.alternateBackground
        p[HIGHLIGHT_COLOR] = s.highlightColor
        p[LOAD_HISTORY] = s.loadHistory
        p[SMOOTH_SCROLLING] = s.smoothScrolling
        p[EMOTES_ENABLED] = s.emotesEnabled
        p[ZERO_WIDTH] = s.zeroWidthEmotes
        p[UNLISTED_7TV] = s.showUnlisted7tv
        p[SEVENTV_EVENTS] = s.sevenTvEvents
        p[SEVENTV_PAINTS] = s.sevenTvPaints
        p[UNREAD_TITLE_BAR] = s.unreadInTitleBar
        p[CHANNEL_TABS] = s.channelTabs
        p[EMOTE_SUGGESTIONS] = s.emoteSuggestions
        p[USER_SUGGESTIONS] = s.userSuggestions
        p[MENTION_WITH_AT] = s.mentionWithAt
        p[SHOW_DELETED] = s.showDeleted
        p[UPDATE_CHECK] = s.updateCheck
        p[INLINE_IMAGES] = s.inlineImages
        p[IMAGE_HOSTS] = s.imageHosts.joinToString(",")
        p[FULL_LINKS] = s.fullLinks
        p[MOBILE_DATA] = s.mobileData.name
        p[CAROUSEL_CHANNELS] = s.carouselChannels
        p[HAPTICS] = s.haptics
        p[KEEP_SCREEN_ON] = s.keepScreenOn
        p[BUBBLES] = s.bubbles
        p[SENDER_AVATARS] = s.senderAvatars
        p[FIRST_MESSAGES] = s.highlightFirstMessages
        p[NAME_COLORS] = s.nameColors.name
        p[MESSAGE_TAP] = s.messageTap.name
        p[NAME_TAP] = s.nameTap.name
        p[COPY_FIRST] = s.copyFirst
        p[BADGE_PROVIDERS] = s.badgeProviders.joinToString(",") { it.name }
        p[EMOTE_PROVIDERS] = s.emoteProviders.joinToString(",") { it.name }
    }

    suspend fun addRecentEmote(name: String) = store.edit { p ->
        val list = p[RECENT_EMOTES].orEmpty().split(' ').filter { it.isNotEmpty() && it != name }
        p[RECENT_EMOTES] = (listOf(name) + list).take(MAX_RECENT).joinToString(" ")
    }

    private companion object {
        fun words(raw: String?): List<String> = raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

        // Only a missing key means defaults; a list the user emptied stays empty.
        fun hosts(raw: String?): List<String> =
            raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: ImageLinks.DEFAULT_HOSTS

        fun badgeProviders(raw: String?): Set<BadgeProvider> =
            raw?.split(',')?.mapNotNull { v -> BadgeProvider.entries.firstOrNull { it.name == v } }?.toSet()
                ?: BadgeProvider.entries.toSet()

        fun emoteProviders(raw: String?): Set<EmoteProvider> =
            raw?.split(',')?.mapNotNull { v -> EmoteProvider.entries.firstOrNull { it.name == v } }?.toSet()
                ?: EmoteProvider.entries.toSet()

        val FONT_SIZE = floatPreferencesKey("font_size")
        val TIMESTAMPS = booleanPreferencesKey("timestamps")
        val TIMESTAMP_FORMAT = stringPreferencesKey("timestamp_format")
        val LIMIT = intPreferencesKey("message_limit")
        val KEYWORDS = stringPreferencesKey("mention_keywords")
        val MUTE_KEYWORDS = stringPreferencesKey("mute_keywords")
        val ANIMATED = booleanPreferencesKey("animated_emotes")
        val SLOW_IDLE_EMOTES = booleanPreferencesKey("slow_idle_emotes")
        val RECENT_EMOTES = stringPreferencesKey("recent_emotes")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val PURE_BLACK = booleanPreferencesKey("pure_black")
        val ALTERNATE_BG = booleanPreferencesKey("alternate_background")
        val HIGHLIGHT_COLOR = intPreferencesKey("highlight_color")
        val LOAD_HISTORY = booleanPreferencesKey("load_history")
        val SMOOTH_SCROLLING = booleanPreferencesKey("smooth_scrolling")
        val EMOTES_ENABLED = booleanPreferencesKey("emotes_enabled")
        val ZERO_WIDTH = booleanPreferencesKey("zero_width_emotes")
        val UNLISTED_7TV = booleanPreferencesKey("show_unlisted_7tv")
        val SEVENTV_EVENTS = booleanPreferencesKey("seventv_events")
        val SEVENTV_PAINTS = booleanPreferencesKey("seventv_paints")
        val UNREAD_TITLE_BAR = booleanPreferencesKey("unread_title_bar")
        val CHANNEL_TABS = booleanPreferencesKey("channel_tabs")
        val EMOTE_SUGGESTIONS = booleanPreferencesKey("emote_suggestions")
        val USER_SUGGESTIONS = booleanPreferencesKey("user_suggestions")
        val MENTION_WITH_AT = booleanPreferencesKey("mention_with_at")
        val SHOW_DELETED = booleanPreferencesKey("show_deleted")
        val UPDATE_CHECK = booleanPreferencesKey("update_check")
        val INLINE_IMAGES = booleanPreferencesKey("inline_images")
        val FULL_LINKS = booleanPreferencesKey("full_links")
        val MOBILE_DATA = stringPreferencesKey("mobile_data")
        val IMAGE_HOSTS = stringPreferencesKey("image_hosts")
        val CAROUSEL_CHANNELS = booleanPreferencesKey("carousel_channels")
        val HAPTICS = booleanPreferencesKey("haptics")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val BUBBLES = booleanPreferencesKey("chat_bubbles")
        val SENDER_AVATARS = booleanPreferencesKey("notification_sender_avatars")
        val EMOTE_PROVIDERS = stringPreferencesKey("emote_providers")
        val BADGE_PROVIDERS = stringPreferencesKey("badge_providers")
        val NAME_COLORS = stringPreferencesKey("name_colors")
        val FIRST_MESSAGES = booleanPreferencesKey("highlight_first_messages")
        val MESSAGE_TAP = stringPreferencesKey("message_tap")
        val NAME_TAP = stringPreferencesKey("name_tap")
        val COPY_FIRST = booleanPreferencesKey("copy_first")
        val SEEN_VERSION = stringPreferencesKey("seen_changelog_version")
        val NOTIFICATION_INTRO_SEEN = booleanPreferencesKey("notification_intro_seen")
        val AVAILABLE_UPDATE = stringPreferencesKey("available_update")
        val UPDATE_CHECKED_AT = longPreferencesKey("update_checked_at")
        const val MAX_RECENT = 40
    }
}
