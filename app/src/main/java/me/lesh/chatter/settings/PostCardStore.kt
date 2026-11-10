package me.lesh.chatter.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import me.lesh.chatter.chat.PostSite

/** Where each site's card choices are stored, one set of keys per site. */
internal object PostCardStore {
    private class Keys(site: PostSite) {
        private val prefix = "post_card_${site.name.lowercase()}_"
        val avatar = booleanPreferencesKey(prefix + "avatar")
        val date = booleanPreferencesKey(prefix + "date")
        val media = booleanPreferencesKey(prefix + "media")
        val counts = booleanPreferencesKey(prefix + "counts")
        val textLines = intPreferencesKey(prefix + "text_lines")
        val pictureSize = stringPreferencesKey(prefix + "picture_size")
        val compact = booleanPreferencesKey(prefix + "compact")
    }

    private val KEYS = PostSite.entries.associateWith(::Keys)

    // Shared by every site before each had its own; still read where a site has no choice yet.
    private val SHARED_AVATAR = booleanPreferencesKey("post_avatar")
    private val SHARED_DATE = booleanPreferencesKey("post_date")
    private val SHARED_MEDIA = booleanPreferencesKey("post_media")
    private val SHARED_COUNTS = booleanPreferencesKey("post_counts")
    private val SHARED_TEXT_LINES = intPreferencesKey("post_text_lines")
    private val SHARED_PICTURE_SIZE = stringPreferencesKey("post_picture_size")

    /** [site]'s own choices; where it has none yet, those every site shared before, then the defaults. */
    fun read(p: Preferences, site: PostSite): PostCardSettings {
        val keys = KEYS.getValue(site)
        val default = PostCardSettings.default(site)
        return PostCardSettings(
            avatar = p[keys.avatar] ?: p[SHARED_AVATAR] ?: default.avatar,
            date = p[keys.date] ?: p[SHARED_DATE] ?: default.date,
            media = p[keys.media] ?: p[SHARED_MEDIA] ?: default.media,
            counts = p[keys.counts] ?: p[SHARED_COUNTS] ?: default.counts,
            textLines = (p[keys.textLines] ?: p[SHARED_TEXT_LINES])?.coerceIn(PostTextLines.RANGE) ?: default.textLines,
            pictureSize = (p[keys.pictureSize] ?: p[SHARED_PICTURE_SIZE])
                ?.let { v -> PostPictureSize.entries.firstOrNull { it.name == v } } ?: default.pictureSize,
            compact = p[keys.compact] ?: default.compact,
        )
    }

    fun write(p: MutablePreferences, site: PostSite, card: PostCardSettings) {
        val keys = KEYS.getValue(site)
        p[keys.avatar] = card.avatar
        p[keys.date] = card.date
        p[keys.media] = card.media
        p[keys.counts] = card.counts
        p[keys.textLines] = card.textLines.coerceIn(PostTextLines.RANGE)
        p[keys.pictureSize] = card.pictureSize.name
        p[keys.compact] = card.compact
    }
}
