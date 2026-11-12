package me.lesh.chatter.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Colors made for Twitch's dark chat, and names on tinted rows, on a light theme. */
class ChatColorTest {
    /** Twitch's cheer tier colors and a pale 7TV paint stop; the grey and teal are too pale on white. */
    private val darkChatColors = listOf(0xFF979797, 0xFF9C3EE8, 0xFF1DB2A5, 0xFF0099FE, 0xFFF43021, 0xFF84CAD1, 0xFFFFF5A0)
        .map { it.toInt() }

    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    @Test
    fun aLightThemeDarkensThemUntilTheyRead() {
        for (argb in darkChatColors) {
            val color = chatColor(argb, dark = false)
            assertTrue("${Integer.toHexString(argb)}: ${contrast(color, Color.White)}", contrast(color, Color.White) >= 4.5f)
        }
    }

    @Test
    fun aDarkThemeLeavesThemAsTheyAre() {
        for (argb in darkChatColors) assertEquals(Color(argb), chatColor(argb, dark = true))
    }

    @Test
    fun aFadingPaintStopKeepsItsAlpha() {
        val halfYellow = 0x80FFF5A0.toInt()
        assertEquals(0.5f, chatColor(halfYellow, dark = false).alpha, 0.01f)
        val clear = 0x00FFFFFF
        assertEquals(Color(clear), chatColor(clear, dark = false))
    }

    @Test
    fun namesOnTintedRowsKeepTheirContrast() {
        val tints = listOf(
            LiveRed.copy(alpha = 0.2f),
            Color(0xFF7236D9).copy(alpha = 0.28f),
            Color(0xFF7E5260).copy(alpha = 0.18f),
            Color(0xFF0000FF).copy(alpha = 0.2f),
        )
        val names = listOf(0xFF13FFFF, 0xFFDAA520, 0xFF00FF7F, 0xFFFFFFFF).map { it.toInt() }
        for (tint in tints) for (argb in names) {
            val behind = tint.compositeOver(Color.White)
            val name = readableNameColor(argb, login = null, dark = false, background = behind)
            assertTrue("${Integer.toHexString(argb)} on ${Integer.toHexString(behind.toArgb())}", contrast(name, behind) >= 4.5f)
        }
    }

    @Test
    fun thePlainChatKeepsTheUsualTarget() {
        assertEquals(MAX_LUMA_ON_LIGHT, maxLumaOnLight(Color.Unspecified), 0f)
        assertEquals(MAX_LUMA_ON_LIGHT, maxLumaOnLight(Color.White), 0f)
        assertTrue(maxLumaOnLight(LiveRed.copy(alpha = 0.2f).compositeOver(Color.White)) < MAX_LUMA_ON_LIGHT)
    }
}
