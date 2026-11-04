package me.lesh.chatter.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightColorTest {
    @Test
    fun customColorsKeepTheChatTextReadableInBothThemes() {
        val schemes = listOf(lightColorScheme(), darkColorScheme(), pureBlack(darkColorScheme()))
        // RGB corners cover the brightest and darkest possible custom colors.
        val colors = listOf(0x00, 0xFF).flatMap { r ->
            listOf(0x00, 0xFF).flatMap { g ->
                listOf(0x00, 0xFF).map { b -> 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b }
            }
        } + 0xFF23B7A5.toInt()
        for (scheme in schemes) {
            for (argb in colors) {
                assertEquals(Color(argb), highlightColor(argb, scheme))
                val background = highlightBackground(argb, scheme).compositeOver(scheme.surface).luminance()
                val text = scheme.onSurface.luminance()
                val contrast = (maxOf(text, background) + 0.05f) / (minOf(text, background) + 0.05f)
                assertTrue("Text contrast for $argb: $contrast", contrast >= 4.5f)
            }
        }
    }
}
