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
 * tinted with the accent; only buttons, links, selections and badges carry [accent], which may be white or grey too. The roles
 * follow Material's baseline scheme, so components look as Material intends them to.
 */
data class ThemePalette(val background: Color, val text: Color, val accent: Color) {

    /** Light text on a darker background; a mid grey background could go either way. */
    val isDark: Boolean get() = text.luminance() > background.luminance()

    /** `1-<background>-<text>-<accent>` in hex, e.g. `1-111214-E7E9EA-FFFFFF`. */
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
        // Baseline's secondary container: tone 30 with only a hint of the accent.
        val secondaryContainer = if (dark) secondary.withHsl(lightness = 0.31f, saturationScale = 0.3f) else secondary.withHsl(lightness = 0.92f)
        val tertiary = accent.rotateHue(TERTIARY_HUE_SHIFT)
        val primaryContainer = container(accent)
        val tertiaryContainer = container(tertiary)
        val base = if (dark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = accent,
            onPrimary = textOn(accent),
            primaryContainer = primaryContainer,
            onPrimaryContainer = textOn(primaryContainer, preferred = mix(accent, far, if (dark) 0.75f else 0.6f)),
            inversePrimary = mix(accent, deep, 0.45f),
            secondary = secondary,
            onSecondary = textOn(secondary),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = textOn(secondaryContainer, preferred = mix(accent, far, if (dark) 0.8f else 0.7f)),
            tertiary = tertiary,
            onTertiary = textOn(tertiary),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = textOn(tertiaryContainer, preferred = mix(tertiary, far, if (dark) 0.75f else 0.6f)),
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
            surfaceBright = if (dark) mix(background, text, 0.17f) else background,
            surfaceDim = if (dark) background else mix(background, text, 0.13f),
            surfaceContainerLowest = mix(background, deep, 0.4f),
            surfaceContainerLow = mix(background, text, 0.04f),
            surfaceContainer = mix(background, text, 0.065f),
            surfaceContainerHigh = mix(background, text, 0.095f),
            surfaceContainerHighest = mix(background, text, 0.13f),
        )
    }

    companion object {
        /** Near black with neutral grey surfaces and white buttons, after X's dark mode. */
        val Dark = ThemePalette(Color(0xFF111214), Color(0xFFE7E9EA), Color.White)

        /**
         * The palette of a [code], or null if it is not one or some of its text would be hard to
         * read, so a typo can never leave the app unreadable.
         */
        fun fromCode(code: String): ThemePalette? {
            val parts = code.trim().split('-')
            if (parts.size != 4 || parts[0] != CODE_VERSION) return null
            val colors = parts.drop(1).map { hex -> parseHex(hex) ?: return null }
            return ThemePalette(colors[0], colors[1], colors[2]).takeIf { it.isReadable() }
        }

        private const val CODE_VERSION = "1"

        /** Material's baseline goes from a purple primary to a pink tertiary, about this far round. */
        private const val TERTIARY_HUE_SHIFT = 60f
    }
}

/**
 * Hints on the most raised cards, and accents drawn as text (links, the snackbar's Undo, warnings
 * in the log) on the background, cards and secondary containers, are the faintest text the scheme
 * draws; text on accents and containers is kept readable by [textOn].
 */
private fun ThemePalette.isReadable(): Boolean {
    val scheme = colorScheme()
    val accentBackgrounds = listOf(scheme.background, scheme.surfaceContainerHighest, scheme.secondaryContainer)
    return contrast(scheme.onSurfaceVariant, scheme.surfaceContainerHighest) >= MIN_TEXT_CONTRAST &&
        listOf(scheme.primary, scheme.tertiary).all { accent ->
            accentBackgrounds.all { contrast(accent, it) >= MIN_TEXT_CONTRAST }
        }
}

/** WCAG AA for body text. */
private const val MIN_TEXT_CONTRAST = 4.5f

private val HexColor = Regex("[0-9A-Fa-f]{6}")

private fun parseHex(hex: String): Color? =
    if (HexColor.matches(hex)) Color(0xFF000000.toInt() or hex.toInt(16)) else null

private fun Color.hex(): String = "%06X".format(java.util.Locale.ROOT, toArgb() and 0xFFFFFF)

/** Mixes in sRGB, not Oklab like Compose's lerp, so a mix towards black keeps the hue exactly. */
private fun mix(from: Color, to: Color, fraction: Float) = Color(
    red = from.red + (to.red - from.red) * fraction,
    green = from.green + (to.green - from.green) * fraction,
    blue = from.blue + (to.blue - from.blue) * fraction,
)

/**
 * Text for [fill]: [preferred], by default a dark or light shade of the fill, or black or white
 * where that does not reach [MIN_TEXT_CONTRAST].
 */
private fun textOn(fill: Color, preferred: Color = shadeOn(fill)): Color = when {
    contrast(fill, preferred) >= MIN_TEXT_CONTRAST -> preferred
    contrast(fill, Color.Black) >= contrast(fill, Color.White) -> Color.Black
    else -> Color.White
}

private fun shadeOn(fill: Color): Color {
    val dark = mix(fill, Color.Black, 0.85f)
    val light = mix(fill, Color.White, 0.9f)
    return if (contrast(fill, dark) >= contrast(fill, light)) dark else light
}

internal fun contrast(a: Color, b: Color): Float {
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
