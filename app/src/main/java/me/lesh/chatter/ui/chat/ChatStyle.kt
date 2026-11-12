package me.lesh.chatter.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import me.lesh.chatter.emotes.HiddenEmotes
import me.lesh.chatter.chat.chatterName
import me.lesh.chatter.settings.EmoteSize
import me.lesh.chatter.settings.NameStyle
import me.lesh.chatter.settings.LineSpacing
import me.lesh.chatter.settings.Settings
import me.lesh.chatter.settings.SpecialMessage
import me.lesh.chatter.settings.TimestampFormat
import me.lesh.chatter.ui.theme.NameColorPalette
import me.lesh.chatter.ui.theme.highlightBackground
import me.lesh.chatter.ui.theme.isAppInDarkTheme
import me.lesh.chatter.ui.theme.isPureBlack

/** What a message row needs besides the message. Changes rarely. */
@Immutable
data class ChatStyle(
    val fontSize: Float,
    val timestamps: TimestampFormat,
    val dark: Boolean,
    val secondaryText: Color,
    val linkColor: Color,
    val mentionBackground: Color,
    /** Background of every other message, or null when alternating backgrounds are off. */
    val alternateBackground: Color?,
    val noticeBackground: Color,
    val firstMessageBackground: Color,
    val accent: Color,
    /** How name colors are adjusted for readability. */
    val nameColors: NameColorPalette,
    /** Nicknames by lowercase login. */
    val nicknames: Map<String, String>,
    val nameStyle: NameStyle = NameStyle.OtherScripts,
    /** Whether holding a message vibrates. */
    val haptics: Boolean,
    /** Hosts whose image links are shown as images. Empty when linked images are off. */
    val imageHosts: List<String>,
    /** Whether GIFs are drawn below the line; false leaves their title as a link. */
    val gifs: Boolean = false,
    /** How links to posts are shown as a card; null leaves them links, as under either saver. */
    val postCard: PostCardStyle? = null,
    /** Whether long links are shortened to site and path start; see [LinkText]. */
    val shortLinks: Boolean = true,
    /** Smallest emote size, to save data; see [me.lesh.chatter.net.DataSaving]. */
    val smallEmotes: Boolean = false,
    /** Frame rate animated emotes ask for; see [EmoteFrameRate]. */
    val emoteFrameRate: Float = EmoteFrameRate.ACTIVE,
    /** Whether names are drawn with their 7TV paint. */
    val paints: Boolean = true,
    /** Special messages shown the way Twitch shows them. */
    val specialMessages: Set<SpecialMessage> = emptySet(),
    /** Background of a message highlighted with channel points. */
    val highlightedBackground: Color = Color.Transparent,
    /** Channel point reward titles by reward id; only the user's own channel's are known. */
    val rewardNames: Map<String, String> = emptyMap(),
    /** Line height as a multiple of [fontSize]; see [LineSpacing]. */
    val lineSpacing: Float = LineSpacing.Normal.factor,
    /** Line under every message, or null when dividers are off. */
    val dividerColor: Color? = null,
    /** Emotes drawn larger, from a sharper image. */
    val largeEmotes: Boolean = false,
    /** Emotes drawn as their name. */
    val hiddenEmotes: HiddenEmotes = HiddenEmotes.NONE,
    /** Kinds of badges left out; see [me.lesh.chatter.badges.Badge.kind]. */
    val hiddenBadges: Set<String> = emptySet(),
)

val ChatStyle.lineHeight: TextUnit get() = (fontSize * lineSpacing).sp

/** This style with linked pictures, GIFs and posts left as links. */
fun ChatStyle.withoutAttachments(): ChatStyle = copy(imageHosts = emptyList(), gifs = false, postCard = null)

/**
 * The channel a message was written in, for lists that mix channels: its picture, and its name for
 * accessibility.
 */
@Immutable
data class ChannelMark(val avatarUrl: String?, val name: String)

/** The name of the chatter [login], shown as the user chose; see [chatterName]. */
fun ChatStyle.nameOf(login: String?, displayName: String?): String =
    chatterName(displayName, login, nameStyle, login?.let { nicknames[it.lowercase()] })

/**
 * The chat style from the settings, shared by the chat screen and the bubble.
 *
 * [powerSave] is Android's battery saver and [saveData] Data Saver or the user's save-data choice
 * on metered networks. Both stop linked images, GIFs and post cards, like they still animated emotes;
 * [saveData] also loads emotes in their smallest size.
 */
@Composable
fun rememberChatStyle(
    settings: Settings,
    nicknames: Map<String, String>,
    rewardNames: Map<String, String> = emptyMap(),
    powerSave: Boolean = false,
    saveData: Boolean = false,
    emoteFrameRate: Float = EmoteFrameRate.ACTIVE,
    hiddenEmotes: HiddenEmotes = HiddenEmotes.NONE,
    hiddenBadges: Set<String> = emptySet(),
): ChatStyle {
    val dark = isAppInDarkTheme()
    val colors = MaterialTheme.colorScheme
    return remember(
        settings.fontSize, settings.lineSpacing, settings.emoteSize, settings.timestamps, settings.nameStyle, settings.highlightColor,
        settings.alternateBackground, settings.messageDividers,
        settings.nameColors, settings.haptics, postCardStyle(settings),
        settings.inlineImages, settings.imageHosts, settings.showGifs, settings.fullLinks, settings.sevenTvPaints, settings.specialMessages, powerSave, saveData, emoteFrameRate, nicknames, hiddenEmotes, hiddenBadges, rewardNames, dark, colors,
    ) {
        ChatStyle(
            fontSize = settings.fontSize,
            timestamps = settings.timestamps,
            dark = dark,
            secondaryText = colors.onSurfaceVariant,
            linkColor = colors.primary,
            mentionBackground = highlightBackground(settings.highlightColor, colors),
            // Every other row slightly lighter; stronger on black, where the usual shade is too
            // faint.
            alternateBackground = if (settings.alternateBackground) {
                colors.onSurface.copy(alpha = if (colors.isPureBlack) 0.08f else 0.05f)
            } else null,
            // On a light theme the pale container all but vanishes, so subs and cheers take a light
            // wash of the accent instead.
            noticeBackground = if (dark) colors.primaryContainer.copy(alpha = 0.35f) else colors.primary.copy(alpha = 0.1f),
            firstMessageBackground = colors.tertiary.copy(alpha = 0.18f),
            accent = colors.primary,
            nameColors = settings.nameColors,
            nicknames = nicknames,
            nameStyle = settings.nameStyle,
            haptics = settings.haptics,
            // Off or either saver means no allowed hosts, so the row checks only one thing. Links
            // stay links.
            imageHosts = if (settings.inlineImages && !powerSave && !saveData) settings.imageHosts else emptyList(),
            gifs = settings.showGifs && !powerSave && !saveData,
            postCard = postCardStyle(settings).takeIf { it.sites.isNotEmpty() && !powerSave && !saveData },
            shortLinks = !settings.fullLinks,
            smallEmotes = saveData,
            emoteFrameRate = emoteFrameRate,
            paints = settings.sevenTvPaints,
            specialMessages = settings.specialMessages,
            highlightedBackground = colors.primary.copy(alpha = 0.28f),
            rewardNames = rewardNames,
            lineSpacing = settings.lineSpacing.factor,
            dividerColor = if (settings.messageDividers) colors.outlineVariant else null,
            largeEmotes = settings.emoteSize == EmoteSize.Large,
            hiddenEmotes = hiddenEmotes,
            hiddenBadges = hiddenBadges,
        )
    }
}
