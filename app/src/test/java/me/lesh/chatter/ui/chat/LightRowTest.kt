package me.lesh.chatter.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import me.lesh.chatter.chat.ChatItem
import me.lesh.chatter.chat.MessageKind
import me.lesh.chatter.settings.TimestampFormat
import me.lesh.chatter.ui.theme.LiveRed
import me.lesh.chatter.ui.theme.NameColorPalette
import me.lesh.chatter.ui.theme.readableNameColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a row's tint does to the text on it, and how image paints are dimmed on a light theme. */
class LightRowTest {
    private val accent = Color(0xFF7236D9)

    private fun style(dark: Boolean) = ChatStyle(
        fontSize = 14f,
        timestamps = TimestampFormat.entries.first(),
        dark = dark,
        secondaryText = Color(0xFF48454F),
        linkColor = accent,
        mentionBackground = LiveRed.copy(alpha = 0.2f),
        alternateBackground = null,
        noticeBackground = accent.copy(alpha = 0.16f),
        firstMessageBackground = Color(0xFF7E5260).copy(alpha = 0.18f),
        accent = accent,
        nameColors = NameColorPalette.HslLuma,
        nicknames = emptyMap(),
        haptics = false,
        imageHosts = emptyList(),
        highlightedBackground = accent.copy(alpha = 0.28f),
        background = if (dark) Color(0xFF141218) else Color.White,
    )

    private fun message(isMention: Boolean = false) =
        ChatItem(id = "1", channel = "forsen", kind = MessageKind.Chat, timestamp = 0, login = "a", color = 0xFFDAA520.toInt(), isMention = isMention)

    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    @Test
    fun aPlainRowAndADarkThemeKeepTheStyle() {
        val light = style(dark = false)
        assertSame(light, light.forRow(message()))
        val dark = style(dark = true)
        assertSame(dark, dark.forRow(message(isMention = true)))
    }

    @Test
    fun aMentionedRowDarkensItsNameAndLinksForTheTint() {
        val row = style(dark = false).forRow(message(isMention = true))
        val behind = row.textBackground
        assertTrue(behind.luminance() < 0.9f)
        val name = readableNameColor(0xFFDAA520.toInt(), "a", dark = false, background = behind)
        assertTrue("name ${contrast(name, behind)}", contrast(name, behind) >= 4.5f)
        assertTrue("link ${contrast(row.linkColor, behind)}", contrast(row.linkColor, behind) >= 4.5f)
    }

    @Test
    fun aPaleImagePaintIsDimmedUntilItReadsOnAverage() {
        val maxLuma = 0.17f
        assertEquals(1f, paintImageDimming(0.1f, maxLuma), 0f)
        val dimming = paintImageDimming(0.8f, maxLuma)
        // A pale grey of luminance 0.8, scaled by the dimming, lands near the target.
        val pale = Color(0.906f, 0.906f, 0.906f)
        val dimmed = Color(pale.red * dimming, pale.green * dimming, pale.blue * dimming)
        assertEquals(maxLuma, dimmed.luminance(), 0.03f)
    }
}
