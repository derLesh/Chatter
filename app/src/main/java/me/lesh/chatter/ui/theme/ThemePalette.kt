package me.lesh.chatter.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/**
 * A color theme as three colors; every Material role is derived from them, so a preset, or a theme
 * someone builds on the website and brings over as a [code], needs nothing else.
 *
 * Surfaces step from [background] towards [text], like the grey cards on X, instead of being
 * tinted with the accent; only buttons, links, selections and badges carry [accent]. The roles
 * follow Material's baseline scheme, so components look as Material intends them to.
 */
data class ThemePalette(val background: Color, val text: Color, val accent: Color) {

    val isDark: Boolean get() = background.luminance() < 0.5f

    /** `1-<background>-<text>-<accent>` in hex, e.g. `1-111214-E7E9EA-BF94FF`. */
    val code: String get() = listOf(background, text, accent).joinToString("-", prefix = "$CODE_VERSION-") { it.hex() }

    fun colorScheme(): ColorScheme {
        val dark = isDark
        // Containers of an accent sit on the background's side of it, their text on the far side.
        // Their lightness is set rather than mixed towards black, which would grey them out; the
        // tones are those of Material's baseline containers.
        fun container(color: Color) = if (dark) color.withHsl(lightness = 0.36f, saturationScale = 0.5f) else color.withHsl(lightness = 0.92f)
        val deep = if (dark) Color.Black else Color.White
        val far = if (dark) Color.White else Color.Black
        val secondary = mix(text, accent, 0.3f)
        val tertiary = accent.rotateHue(TERTIARY_HUE_SHIFT)
        val base = if (dark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = accent,
            onPrimary = readableOn(accent),
            primaryContainer = container(accent),
            onPrimaryContainer = mix(accent, far, if (dark) 0.75f else 0.6f),
            inversePrimary = mix(accent, deep, 0.45f),
            secondary = secondary,
            onSecondary = readableOn(secondary),
            secondaryContainer = mix(background, accent, if (dark) 0.2f else 0.12f),
            onSecondaryContainer = mix(accent, far, if (dark) 0.8f else 0.7f),
            tertiary = tertiary,
            onTertiary = readableOn(tertiary),
            tertiaryContainer = container(tertiary),
            onTertiaryContainer = mix(tertiary, far, if (dark) 0.75f else 0.6f),
            background = background,
            onBackground = text,
            surface = background,
            onSurface = text,
            surfaceVariant = mix(background, text, 0.16f),
            onSurfaceVariant = mix(text, background, 0.25f),
            surfaceTint = accent,
            inverseSurface = text,
            inverseOnSurface = mix(background, text, 0.1f),
            outline = mix(text, background, 0.5f),
            outlineVariant = mix(background, text, 0.16f),
            surfaceBright = mix(background, text, 0.17f),
            surfaceDim = background,
            surfaceContainerLowest = mix(background, deep, 0.4f),
            surfaceContainerLow = mix(background, text, 0.04f),
            surfaceContainer = mix(background, text, 0.065f),
            surfaceContainerHigh = mix(background, text, 0.095f),
            surfaceContainerHighest = mix(background, text, 0.13f),
        )
    }

    companion object {
        /** Near black with neutral grey surfaces, after X's dark mode, and Twitch's purple. */
        val Dark = ThemePalette(Color(0xFF111214), Color(0xFFE7E9EA), Color(0xFFBF94FF))

        /**
         * The palette of a [code], or null if it is not one or its text would be hard to read on its
         * background, so a typo can never leave the app unreadable.
         */
        fun fromCode(code: String): ThemePalette? {
            val parts = code.trim().split('-')
            if (parts.size != 4 || parts[0] != CODE_VERSION) return null
            val colors = parts.drop(1).map { hex -> parseHex(hex) ?: return null }
            val palette = ThemePalette(colors[0], colors[1], colors[2])
            return palette.takeIf { contrast(it.background, it.text) >= MIN_TEXT_CONTRAST }
        }

        private const val CODE_VERSION = "1"

        /** WCAG AA for body text. */
        private const val MIN_TEXT_CONTRAST = 4.5f

        /** Material's baseline goes from a purple primary to a pink tertiary, about this far round. */
        private const val TERTIARY_HUE_SHIFT = 60f
    }
}

private fun parseHex(hex: String): Color? {
    val digits = hex.removePrefix("#")
    if (digits.length != 6) return null
    val rgb = digits.toIntOrNull(16) ?: return null
    return Color(0xFF000000.toInt() or rgb)
}

private fun Color.hex(): String = "%06X".format(java.util.Locale.ROOT, toArgb() and 0xFFFFFF)

/** Mixes in sRGB, not Oklab like Compose's lerp, so a mix towards black keeps the hue exactly. */
private fun mix(from: Color, to: Color, fraction: Float) = Color(
    red = from.red + (to.red - from.red) * fraction,
    green = from.green + (to.green - from.green) * fraction,
    blue = from.blue + (to.blue - from.blue) * fraction,
)

/** A dark or light shade of [color], whichever reads better on it. */
private fun readableOn(color: Color): Color {
    val dark = mix(color, Color.Black, 0.75f)
    val light = mix(color, Color.White, 0.9f)
    return if (contrast(color, dark) >= contrast(color, light)) dark else light
}

private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

private fun Color.rotateHue(degrees: Float): Color {
    val (hue, saturation, lightness) = toHsl()
    return hslColor((hue + degrees).mod(360f), saturation, lightness)
}

private fun Color.withHsl(lightness: Float, saturationScale: Float = 1f): Color {
    val (hue, saturation) = toHsl()
    return hslColor(hue, saturation * saturationScale, lightness)
}
