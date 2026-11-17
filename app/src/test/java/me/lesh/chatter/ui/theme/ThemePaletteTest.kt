package me.lesh.chatter.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePaletteTest {

    @Test
    fun codeRoundTrips() {
        val code = ThemePalette.Dark.code
        assertEquals("1-111214-E7E9EA-BF94FF", code)
        assertEquals(ThemePalette.Dark, ThemePalette.fromCode(code))
    }

    @Test
    fun codeAcceptsLowerCaseHashesAndSpaces() {
        assertEquals(ThemePalette.Dark, ThemePalette.fromCode(" 1-#111214-e7e9ea-bf94ff\n"))
    }

    @Test
    fun rejectsWhatIsNotACode() {
        listOf("", "1", "2-111214-E7E9EA-BF94FF", "1-111214-E7E9EA", "1-111214-E7E9EA-BF94FF-000000", "1-11121-E7E9EA-BF94FF", "1-11121G-E7E9EA-BF94FF")
            .forEach { assertNull(it, ThemePalette.fromCode(it)) }
    }

    @Test
    fun rejectsTextTooFaintToRead() {
        assertNull(ThemePalette.fromCode("1-111214-333333-BF94FF"))
    }

    @Test
    fun darkSurfacesStepTowardsTheTextAndStayGrey() {
        val scheme = ThemePalette.Dark.colorScheme()
        val steps = listOf(
            scheme.surfaceContainerLowest, scheme.surface, scheme.surfaceContainerLow, scheme.surfaceContainer,
            scheme.surfaceContainerHigh, scheme.surfaceContainerHighest,
        )
        assertEquals(steps.sortedBy { it.luminance() }, steps)
        steps.forEach { assertTrue("$it is tinted", maxOf(it.red, it.green, it.blue) - minOf(it.red, it.green, it.blue) < 0.02f) }
    }

    @Test
    fun lightPaletteGetsReadableAccentText() {
        val scheme = ThemePalette(Color.White, Color(0xFF1B1A1F), Color(0xFF7236D9)).colorScheme()
        assertTrue(scheme.onPrimary.luminance() > scheme.primary.luminance())
        assertTrue(scheme.primaryContainer.luminance() > scheme.primary.luminance())
    }
}
