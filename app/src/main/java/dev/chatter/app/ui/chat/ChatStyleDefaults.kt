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
 */
@Composable
fun rememberChatStyle(settings: Settings, nicknames: Map<String, String>): ChatStyle {
    val dark = isAppInDarkTheme()
    val colors = MaterialTheme.colorScheme
    return remember(
        settings.fontSize, settings.timestamps, settings.highlightColor, settings.alternateBackground,
        settings.showDeleted, settings.nameColors, settings.highlightFirstMessages, nicknames, dark, colors,
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
            showDeleted = settings.showDeleted,
            nameColors = settings.nameColors,
            nicknames = nicknames,
        )
    }
}
