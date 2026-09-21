package dev.chatter.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.chatter.app.settings.Settings
import dev.chatter.app.ui.theme.highlightBackground
import dev.chatter.app.ui.theme.isAppInDarkTheme

/**
 * The look of a chat message as the settings describe it. Shared by the chat screen and the
 * bubble, so both render a message exactly the same way.
 *
 * [powerSave] is Android's battery saver: the phone being asked to do less. Fetching a picture
 * for every link somebody posts is the opposite of that, so it stops for as long as the saver is
 * on — the same rule animated emotes already follow.
 */
@Composable
fun rememberChatStyle(
    settings: Settings,
    nicknames: Map<String, String>,
    powerSave: Boolean = false,
): ChatStyle {
    val dark = isAppInDarkTheme()
    val colors = MaterialTheme.colorScheme
    return remember(
        settings.fontSize, settings.timestamps, settings.highlightColor, settings.alternateBackground,
        settings.nameColors, settings.highlightFirstMessages, settings.haptics,
        settings.inlineImages, settings.imageHosts, powerSave, nicknames, dark, colors,
    ) {
        ChatStyle(
            fontSize = settings.fontSize,
            timestamps = settings.timestamps,
            dark = dark,
            secondaryText = colors.onSurfaceVariant,
            linkColor = colors.primary,
            mentionBackground = highlightBackground(settings.highlightColor, colors),
            alternateBackground = if (settings.alternateBackground) colors.onSurface.copy(alpha = 0.05f) else null,
            noticeBackground = colors.primaryContainer.copy(alpha = 0.35f),
            firstMessageBackground = if (settings.highlightFirstMessages) colors.tertiary.copy(alpha = 0.18f) else null,
            accent = colors.primary,
            nameColors = settings.nameColors,
            nicknames = nicknames,
            haptics = settings.haptics,
            // Off, or the battery saver, is the same as allowing nobody, so the row only ever
            // reads one thing to decide whether a picture is fetched.
            imageHosts = if (settings.inlineImages && !powerSave) settings.imageHosts else emptyList(),
        )
    }
}
