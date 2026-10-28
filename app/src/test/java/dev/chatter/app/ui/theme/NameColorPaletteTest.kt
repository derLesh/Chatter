package dev.chatter.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How each palette makes a picked name color readable, on both backgrounds. */
class NameColorPaletteTest {
    /** Bright colors chatters really pick: cyan, goldenrod, spring green. */
    private val bright = listOf(Color(0xFF13FFFF), Color(0xFFDAA520), Color(0xFF00FF7F))

    /** Dark blue and dark red, which a dark background has to lift. */
    private val dark = listOf(Color(0xFF0000FF), Color(0xFF8B0000))

    private val adjusting = NameColorPalette.entries - NameColorPalette.None - NameColorPalette.Twitch

    @Test
    fun brightColorsGetDarkerOnALightBackgroundButNotBlack() {
        for (palette in adjusting) for (color in bright) {
            val luminance = palette.adjust(color, dark = false).luminance()
            assertTrue("$palette $color: $luminance", luminance <= 0.40f)
            assertTrue("$palette $color came out nearly black: $luminance", luminance >= 0.08f)
        }
    }

    @Test
    fun darkColorsGetLighterOnADarkBackground() {
        for (palette in adjusting) for (color in dark) {
            assertTrue("$palette $color", palette.adjust(color, dark = true).luminance() >= 0.18f)
        }
    }

    @Test
    fun aReadableColorIsLeftAlone() {
        val orange = Color(0xFFFF7F24)
        for (palette in adjusting) assertEquals("$palette", orange, palette.adjust(orange, dark = false))
    }
}
