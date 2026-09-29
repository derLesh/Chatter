package dev.chatter.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.chatter.app.settings.Settings
import dev.chatter.app.ui.theme.highlightBackground
import dev.chatter.app.ui.theme.isAppInDarkTheme
import dev.chatter.app.ui.theme.isPureBlack

/**
 * The look of a chat message as the settings describe it. Shared by the chat screen and the
 * bubble, so both render a message exactly the same way.
 *
 * [powerSave] is Android's battery saver: the phone being asked to do less. Fetching a picture
 * for every link somebody posts is the opposite of that, so it stops for as long as the saver is
 * on — the same rule animated emotes already follow.
 *
 * [saveData] is Data Saver, or the user's own choice to save data on a metered network. It does
 * the same, and has the emotes fetched in their smallest size on top of it: pictures are where
 * the data goes, and the chat reads the same without them.
 */
@Composable
fun rememberChatStyle(
    settings: Settings,
    nicknames: Map<String, String>,
    powerSave: Boolean = false,
    saveData: Boolean = false,
): ChatStyle {
    val dark = isAppInDarkTheme()
    val colors = MaterialTheme.colorScheme
    return remember(
        settings.fontSize, settings.timestamps, settings.highlightColor, settings.alternateBackground,
        settings.nameColors, settings.highlightFirstMessages, settings.haptics,
        settings.inlineImages, settings.imageHosts, settings.fullLinks, powerSave, saveData, nicknames, dark, colors,
    ) {
        ChatStyle(
            fontSize = settings.fontSize,
            timestamps = settings.timestamps,
            dark = dark,
            secondaryText = colors.onSurfaceVariant,
            linkColor = colors.primary,
            mentionBackground = highlightBackground(settings.highlightColor, colors),
            // Every other row a shade lighter. Over black the usual shade is too faint to follow
            // along a line with, so it is a little stronger there.
            alternateBackground = if (settings.alternateBackground) {
                colors.onSurface.copy(alpha = if (colors.isPureBlack) 0.08f else 0.05f)
            } else null,
            noticeBackground = colors.primaryContainer.copy(alpha = 0.35f),
            firstMessageBackground = if (settings.highlightFirstMessages) colors.tertiary.copy(alpha = 0.18f) else null,
            accent = colors.primary,
            nameColors = settings.nameColors,
            nicknames = nicknames,
            haptics = settings.haptics,
            // Off, or either saver, is the same as allowing nobody, so the row only ever reads
            // one thing to decide whether a picture is fetched. The link stays either way.
            imageHosts = if (settings.inlineImages && !powerSave && !saveData) settings.imageHosts else emptyList(),
            shortLinks = !settings.fullLinks,
            smallEmotes = saveData,
        )
    }
}
