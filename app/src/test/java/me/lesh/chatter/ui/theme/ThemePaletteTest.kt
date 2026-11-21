package me.lesh.chatter.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePaletteTest {

    @Test
    fun codeRoundTrips() {
        val code = ThemePalette.Dark.code
        assertEquals("1-111214-E7E9EA-FFFFFF", code)
        assertEquals(ThemePalette.Dark, ThemePalette.fromCode(code))
    }

    @Test
    fun codeAcceptsLowerCaseAndSpaces() {
        assertEquals(ThemePalette.Dark, ThemePalette.fromCode(" 1-111214-e7e9ea-ffffff\n"))
    }

    @Test
    fun rejectsWhatIsNotACode() {
        listOf(
            "", "1", "2-111214-E7E9EA-CDB0FF", "1-111214-E7E9EA", "1-111214-E7E9EA-CDB0FF-000000",
            "1-11121-E7E9EA-CDB0FF", "1-11121G-E7E9EA-CDB0FF", "1-111214-E7E9EA-+DB0FF", "1-#111214-E7E9EA-CDB0FF",
        ).forEach { assertNull(it, ThemePalette.fromCode(it)) }
    }

    @Test
    fun rejectsTextTooFaintToRead() {
        assertNull("text", ThemePalette.fromCode("1-111214-333333-CDB0FF"))
        assertNull("hints", ThemePalette.fromCode("1-111214-7D7D7D-CDB0FF"))
        assertNull("links", ThemePalette.fromCode("1-303030-E7E9EA-6441A5"))
        assertNull("accent on a secondary container", ThemePalette.fromCode("1-111214-E7E9EA-9A9A9A"))
        assertNull("tertiary on a card", ThemePalette.fromCode("1-3E3E3E-FFFFFF-BBDDCC"))
    }

    @Test
    fun textOnAccentsAndContainersIsReadable() {
        listOf(
            ThemePalette.Dark,
            ThemePalette(Color(0xFF111214), Color(0xFFE7E9EA), Color(0xFFCDB0FF)),
            ThemePalette(Color(0xFF111214), Color.White, Color(0xFF808080)),
            ThemePalette(Color(0xFF161616), Color.White, Color(0xFF00FF00)),
            ThemePalette(Color.White, Color(0xFF1B1A1F), Color(0xFF7236D9)),
        ).forEach { palette ->
            val s = palette.colorScheme()
            listOf(
                s.primary to s.onPrimary, s.primaryContainer to s.onPrimaryContainer,
                s.secondary to s.onSecondary, s.secondaryContainer to s.onSecondaryContainer,
                s.tertiary to s.onTertiary, s.tertiaryContainer to s.onTertiaryContainer,
            ).forEach { (fill, text) -> assertTrue("$text on $fill in $palette", contrast(fill, text) >= 4.5f) }
        }
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
    fun midGreyBackgroundWithDarkTextIsLight() {
        val palette = ThemePalette(Color(0xFFAAAAAA), Color(0xFF111111), Color(0xFF9146FF))
        assertFalse(palette.isDark)
        val scheme = palette.colorScheme()
        assertTrue(scheme.surfaceBright.luminance() > scheme.surfaceDim.luminance())
    }
}
