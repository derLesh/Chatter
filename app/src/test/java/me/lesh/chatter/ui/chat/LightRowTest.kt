package me.lesh.chatter.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import me.lesh.chatter.chat.ChatItem
import me.lesh.chatter.chat.MessageKind
import me.lesh.chatter.chat.PowerUp
import me.lesh.chatter.settings.SpecialMessage
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
        specialMessages = SpecialMessage.entries.toSet(),
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
    fun everyTintedRowKeepsItsNameLinksAndLabelsReadable() {
        val rows = mapOf(
            "mention" to message(isMention = true),
            "mention rule" to message().copy(highlight = ChatItem.MENTION_HIGHLIGHT),
            "blue rule" to message().copy(highlight = 0xFF1E90FF.toInt()),
            "channel points" to message().copy(powerUp = PowerUp.Highlighted),
            "first message" to message().copy(isFirstMessage = true),
            "sub" to message().copy(kind = MessageKind.UserNotice),
            "cheer" to message().copy(bits = 100),
        )
        val names = listOf(0xFFDAA520, 0xFF13FFFF, 0xFF00FF7F, 0xFFFFFFFF).map { it.toInt() }
        for ((kind, item) in rows) {
            val row = style(dark = false).forRow(item)
            val behind = row.textBackground
            assertTrue("$kind is not tinted", behind.luminance() < 0.99f)
            for (argb in names) {
                val name = readableNameColor(argb, "a", dark = false, background = behind)
                assertTrue("$kind name ${contrast(name, behind)}", contrast(name, behind) >= 4.5f)
            }
            assertTrue("$kind link ${contrast(row.linkColor, behind)}", contrast(row.linkColor, behind) >= 4.5f)
            assertTrue("$kind accent ${contrast(row.accent, behind)}", contrast(row.accent, behind) >= 4.5f)
        }
    }

    @Test
    fun theSameColorIsDarkerOnATintThanOnWhite() {
        val goldenrod = 0xFFDAA520.toInt()
        val onWhite = readableNameColor(goldenrod, "a", dark = false)
        val onTint = readableNameColor(goldenrod, "a", dark = false, background = style(dark = false).forRow(message(isMention = true)).textBackground)
        assertTrue("${onTint.luminance()} vs ${onWhite.luminance()}", onTint.luminance() < onWhite.luminance())
        assertEquals(onWhite, readableNameColor(goldenrod, "a", dark = false))
    }

    @Test
    fun aGreySurfaceDarkensEvenPlainRows() {
        val sheet = Color(0xFFEBE9EF)
        val row = style(dark = false).over(sheet).forRow(message())
        assertEquals(sheet, row.textBackground)
        val name = readableNameColor(0xFFFFFFFF.toInt(), "a", dark = false, background = row.textBackground)
        assertTrue("${contrast(name, sheet)}", contrast(name, sheet) >= 4.5f)
        val mention = style(dark = false).over(sheet).forRow(message(isMention = true))
        assertTrue(mention.textBackground.luminance() < sheet.luminance())
    }

    @Test
    fun anImagePaintWithAWhiteStripIsDimmedUntilTheStripReads() {
        // Only the top eighth is white: a name shows just that strip, so the average would not do.
        val pixels = IntArray(64 * 64) { i -> if (i < 64 * 8) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val lightest = lightestLuminance(pixels)
        assertEquals(1f, lightest, 0.001f)
        val maxLuma = 0.17f
        val dimming = paintImageDimming(lightest, maxLuma)
        val dimmedWhite = Color(dimming, dimming, dimming)
        assertTrue("${contrast(dimmedWhite, Color.White)}", contrast(dimmedWhite, Color.White) >= 4.4f)
        assertEquals(maxLuma, dimmedWhite.luminance(), 0.02f)
    }

    @Test
    fun aDarkImagePaintAndClearPixelsAreLeftAlone() {
        val pixels = intArrayOf(0xFF202020.toInt(), 0x00FFFFFF, 0x40FFFFFF)
        assertEquals(1f, paintImageDimming(lightestLuminance(pixels), 0.17f), 0f)
    }
}
