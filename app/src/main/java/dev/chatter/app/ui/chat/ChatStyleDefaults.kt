package dev.chatter.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.chatter.app.settings.Settings
import dev.chatter.app.ui.theme.highlightBackground
import dev.chatter.app.ui.theme.isAppInDarkTheme
import dev.chatter.app.ui.theme.isPureBlack

/**
 * The chat style from the settings, shared by the chat screen and the bubble.
 *
 * [powerSave] is Android's battery saver and [saveData] Data Saver or the user's save-data choice
 * on metered networks. Both stop linked images, like they still animated emotes; [saveData] also
 * loads emotes in their smallest size.
 */
@Composable
fun rememberChatStyle(
    settings: Settings,
    nicknames: Map<String, String>,
    powerSave: Boolean = false,
    saveData: Boolean = false,
    emoteFrameRate: Float = EmoteFrameRate.ACTIVE,
): ChatStyle {
    val dark = isAppInDarkTheme()
    val colors = MaterialTheme.colorScheme
    return remember(
        settings.fontSize, settings.timestamps, settings.highlightColor, settings.alternateBackground,
        settings.nameColors, settings.highlightFirstMessages, settings.haptics,
        settings.inlineImages, settings.imageHosts, settings.fullLinks, settings.sevenTvPaints, powerSave, saveData, emoteFrameRate, nicknames, dark, colors,
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
            noticeBackground = colors.primaryContainer.copy(alpha = 0.35f),
            firstMessageBackground = if (settings.highlightFirstMessages) colors.tertiary.copy(alpha = 0.18f) else null,
            accent = colors.primary,
            nameColors = settings.nameColors,
            nicknames = nicknames,
            haptics = settings.haptics,
            // Off or either saver means no allowed hosts, so the row checks only one thing. Links
            // stay links.
            imageHosts = if (settings.inlineImages && !powerSave && !saveData) settings.imageHosts else emptyList(),
            shortLinks = !settings.fullLinks,
            smallEmotes = saveData,
            emoteFrameRate = emoteFrameRate,
            paints = settings.sevenTvPaints,
        )
    }
}
