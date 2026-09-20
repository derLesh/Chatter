package dev.chatter.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.pow

/**
 * How the name colors users picked on Twitch are made readable on the chat background.
 *
 * Dark blue on a black background is unreadable, so the color is pushed towards the background's
 * opposite. Which way that happens changes how the palette feels, so the choice is the user's
 * (the same modes FrankerFaceZ offers).
 */
enum class NameColorPalette {
    /** Names stay in the normal text color. */
    None,

    /** Exactly the color Twitch reports, however unreadable it is. */
    Twitch,

    /** Brightens/darkens in HSL until the color is readable. Keeps hue and saturation. */
    HslLuma,

    /** Like [HslLuma], but the lightness wraps around instead of running into white/black. */
    HslLoop,

    /** Like [HslLuma] in CIELUV, which keeps the perceived hue better than HSL does. */
    LuvLuma,

    /** Shifts the RGB channels and wraps what runs over, which also changes the hue. */
    RgbLoop,
}

// Contrast targets against the chat background, in relative (WCAG) luminance.
private const val MIN_LUMA_ON_DARK = 0.18f
private const val MAX_LUMA_ON_LIGHT = 0.40f

/** True if [color] is already readable on this background and needs no adjustment at all. */
private fun readable(color: Color, dark: Boolean): Boolean =
    if (dark) color.luminance() >= MIN_LUMA_ON_DARK else color.luminance() <= MAX_LUMA_ON_LIGHT

/**
 * Applies the palette to a name color. Returns [Color.Unspecified] for [NameColorPalette.None],
 * which leaves the name in the surrounding text color.
 */
fun NameColorPalette.adjust(base: Color, dark: Boolean): Color = when {
    this == NameColorPalette.None -> Color.Unspecified
    this == NameColorPalette.Twitch || readable(base, dark) -> base
    this == NameColorPalette.HslLuma -> {
        val hsl = base.toHsl()
        solveLightness(dark, hsl[2]) { l -> hslColor(hsl[0], hsl[1], l) }
    }
    this == NameColorPalette.LuvLuma -> {
        val luv = base.toLuv()
        solveLightness(dark, luv[0] / 100f) { l -> luvColor(l * 100f, luv[1], luv[2]) }
    }
    this == NameColorPalette.HslLoop -> {
        val hsl = base.toHsl()
        loop(dark) { step -> hslColor(hsl[0], hsl[1], (hsl[2] + step) % 1f) }
    }
    else -> loop(dark) { step ->
        Color((base.red + step) % 1f, (base.green + step) % 1f, (base.blue + step) % 1f)
    }
}

/**
 * Finds the smallest lightness change (in whatever space [build] works in) that makes the color
 * readable. On a dark background the lightness only goes up, on a light one only down, so the
 * color never jumps past the one the user picked.
 */
private inline fun solveLightness(dark: Boolean, current: Float, build: (Float) -> Color): Color {
    var lo = if (dark) current else 0f
    var hi = if (dark) 1f else current
    // The extreme end is the fallback: if even white is too dark, nothing else will do better.
    var best = build(if (dark) hi else lo)
    repeat(STEPS) {
        val mid = (lo + hi) / 2f
        val color = build(mid)
        if (readable(color, dark)) {
            best = color
            if (dark) hi = mid else lo = mid
        } else {
            if (dark) lo = mid else hi = mid
        }
    }
    return best
}

/** Walks the color around its space in fixed steps and stops at the first readable one. */
private inline fun loop(dark: Boolean, build: (Float) -> Color): Color {
    var fallback = build(LOOP_STEP)
    for (i in 1..LOOP_COUNT) {
        val color = build(i * LOOP_STEP)
        if (readable(color, dark)) return color
        // Nothing fits: keep whichever came closest to the background's opposite.
        if (if (dark) color.luminance() > fallback.luminance() else color.luminance() < fallback.luminance()) {
            fallback = color
        }
    }
    return fallback
}

private const val STEPS = 12
private const val LOOP_STEP = 1f / 12f
private const val LOOP_COUNT = 11

// ---- Color spaces ----------------------------------------------------------------------------

/** [hue (0..360), saturation, lightness] */
internal fun Color.toHsl(): FloatArray {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val delta = max - min
    val l = (max + min) / 2f
    if (delta == 0f) return floatArrayOf(0f, 0f, l)
    val s = delta / (1f - abs(2f * l - 1f))
    val h = when (max) {
        red -> 60f * (((green - blue) / delta) % 6f)
        green -> 60f * ((blue - red) / delta + 2f)
        else -> 60f * ((red - green) / delta + 4f)
    }
    return floatArrayOf((h + 360f) % 360f, s.coerceIn(0f, 1f), l)
}

internal fun hslColor(hue: Float, saturation: Float, lightness: Float): Color {
    val l = lightness.coerceIn(0f, 1f)
    val c = (1f - abs(2f * l - 1f)) * saturation
    val h = (hue % 360f) / 60f
    val x = c * (1f - abs(h % 2f - 1f))
    val (r, g, b) = when (h.toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f))
}

// CIELUV around the D65 white point, the same reference sRGB uses.
private const val WHITE_X = 95.047f
private const val WHITE_Y = 100f
private const val WHITE_Z = 108.883f
private val WHITE_U = 4f * WHITE_X / (WHITE_X + 15f * WHITE_Y + 3f * WHITE_Z)
private val WHITE_V = 9f * WHITE_Y / (WHITE_X + 15f * WHITE_Y + 3f * WHITE_Z)

private fun gammaToLinear(v: Float): Float =
    if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

private fun linearToGamma(v: Float): Float =
    if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f

/** [L* (0..100), u*, v*] */
internal fun Color.toLuv(): FloatArray {
    val r = gammaToLinear(red) * 100f
    val g = gammaToLinear(green) * 100f
    val b = gammaToLinear(blue) * 100f
    val x = r * 0.4124f + g * 0.3576f + b * 0.1805f
    val y = r * 0.2126f + g * 0.7152f + b * 0.0722f
    val z = r * 0.0193f + g * 0.1192f + b * 0.9505f
    val denominator = x + 15f * y + 3f * z
    if (denominator == 0f) return floatArrayOf(0f, 0f, 0f)
    val yr = y / WHITE_Y
    val l = if (yr > 0.008856f) 116f * yr.pow(1f / 3f) - 16f else 903.3f * yr
    return floatArrayOf(l, 13f * l * (4f * x / denominator - WHITE_U), 13f * l * (9f * y / denominator - WHITE_V))
}

internal fun luvColor(lStar: Float, u: Float, v: Float): Color {
    val l = lStar.coerceIn(0f, 100f)
    if (l <= 0f) return Color.Black
    val y = if (l > 8f) WHITE_Y * ((l + 16f) / 116f).pow(3) else WHITE_Y * l / 903.3f
    // Grays have no chroma to preserve, and the formulas below would divide by zero.
    if (abs(u) < 1e-4f && abs(v) < 1e-4f) {
        val gray = linearToGamma((y / 100f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        return Color(gray, gray, gray)
    }
    val a = (52f * l / (u + 13f * l * WHITE_U) - 1f) / 3f
    val d = y * (39f * l / (v + 13f * l * WHITE_V) - 5f)
    val x = (d + 5f * y) / (a + 1f / 3f)
    val z = x * a - 5f * y
    val r = (x * 0.032406f + y * -0.015372f + z * -0.004986f)
    val g = (x * -0.009689f + y * 0.018758f + z * 0.000415f)
    val b = (x * 0.000557f + y * -0.002040f + z * 0.010570f)
    return Color(
        linearToGamma(r.coerceIn(0f, 1f)).coerceIn(0f, 1f),
        linearToGamma(g.coerceIn(0f, 1f)).coerceIn(0f, 1f),
        linearToGamma(b.coerceIn(0f, 1f)).coerceIn(0f, 1f),
    )
}
