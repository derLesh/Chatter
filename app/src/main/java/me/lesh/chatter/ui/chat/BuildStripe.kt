package me.lesh.chatter.ui.chat

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.lesh.chatter.BuildConfig

/**
 * A stripe at the start of the input in the look of the build's own icon, fading into the field, so
 * Dev and Nightly can be told from Chatter while typing. The release has none.
 */
object BuildStripe {
    internal val look: BuildStripeLook? = when {
        BuildConfig.NIGHTLY -> BuildStripeLook.NightSky
        BuildConfig.DEV_BUILD -> BuildStripeLook.Blueprint
        else -> null
    }

    /** Whether this build has a stripe, and with it the setting to hide it. */
    val offered: Boolean = look != null
}

/** Drawn here rather than from the icon backgrounds, whose circle and moon only work as a square. */
internal enum class BuildStripeLook { Blueprint, NightSky }

private val SOLID_WIDTH = 20.dp
private val FADE_WIDTH = 44.dp

/**
 * Draws the [BuildStripe] over [fieldColor], the field's own background, into which it fades. On a
 * light field it is washed out, so the dark icons on it stay readable.
 */
internal fun Modifier.buildStripe(shown: Boolean, fieldColor: Color): Modifier {
    val look = BuildStripe.look
    if (!shown || look == null) return this
    return drawWithCache {
        val solid = SOLID_WIDTH.toPx()
        val width = (solid + FADE_WIDTH.toPx()).coerceAtMost(size.width)
        val stripe = Size(width, size.height)
        val pattern = when (look) {
            BuildStripeLook.Blueprint -> blueprint(stripe)
            BuildStripeLook.NightSky -> nightSky(stripe)
        }
        val wash = fieldColor.copy(alpha = 0.6f).takeIf { fieldColor.luminance() > 0.5f }
        val fade = Brush.horizontalGradient(
            0f to Color.Transparent,
            (solid / width).coerceAtMost(1f) to Color.Transparent,
            1f to fieldColor,
            endX = width,
        )
        // The field's buttons and text start on the right in right-to-left languages.
        val mirrored = layoutDirection == LayoutDirection.Rtl
        onDrawBehind {
            scale(scaleX = if (mirrored) -1f else 1f, scaleY = 1f) {
                clipRect(right = width) {
                    pattern()
                    if (wash != null) drawRect(wash, size = stripe)
                }
                drawRect(fade, size = stripe)
            }
        }
    }
}

/** The Dev build icon's blue with its grid, every third line stronger. */
private fun CacheDrawScope.blueprint(stripe: Size): DrawScope.() -> Unit {
    val background = Brush.radialGradient(
        listOf(Color(0xFF2B6CC4), Color(0xFF17478A)),
        center = Offset(stripe.width * 0.3f, stripe.height / 2),
        radius = stripe.width,
    )
    val cell = 6.dp.toPx()
    val lines = buildList {
        var column = 1
        while (column * cell < stripe.width) {
            add(GridLine(column % 3 == 0, Offset(column * cell, 0f), Offset(column * cell, stripe.height)))
            column++
        }
        // From the middle out, so the strong lines sit the same way in a short field and a tall one.
        val middle = stripe.height / 2
        var row = 0
        while (row * cell < middle) {
            val major = row % 3 == 0
            add(GridLine(major, Offset(0f, middle - row * cell), Offset(stripe.width, middle - row * cell)))
            if (row > 0) add(GridLine(major, Offset(0f, middle + row * cell), Offset(stripe.width, middle + row * cell)))
            row++
        }
    }
    val major = Color.White.copy(alpha = 0.3f) to 1.dp.toPx()
    val minor = Color.White.copy(alpha = 0.14f) to 0.5.dp.toPx()
    return {
        drawRect(background, size = stripe)
        for (line in lines) {
            val (color, stroke) = if (line.major) major else minor
            drawLine(color, line.start, line.end, strokeWidth = stroke)
        }
    }
}

private class GridLine(val major: Boolean, val start: Offset, val end: Offset)

/** Where the stars sit, as parts of the stripe's width and height, with their radius in dp. */
private class Star(val x: Float, val y: Float, val radius: Float, val alpha: Float)

private val STARS = listOf(
    Star(0.10f, 0.72f, 1.0f, 0.95f),
    Star(0.32f, 0.22f, 0.7f, 0.6f),
    Star(0.40f, 0.62f, 0.9f, 0.85f),
    Star(0.20f, 0.42f, 0.5f, 0.5f),
    Star(0.55f, 0.35f, 0.6f, 0.5f),
    Star(0.62f, 0.78f, 0.5f, 0.45f),
)

/** The Nightly icon's purple sky with a few stars and one four-pointed sparkle. */
private fun CacheDrawScope.nightSky(stripe: Size): DrawScope.() -> Unit {
    val sky = Brush.verticalGradient(listOf(Color(0xFF140B2E), Color(0xFF2A1452), Color(0xFF4B2380)), endY = stripe.height)
    val sparkle = sparkle(Offset(stripe.width * 0.18f, stripe.height * 0.26f), reach = 3.dp.toPx())
    return {
        drawRect(sky, size = stripe)
        for (star in STARS) {
            drawCircle(
                Color.White.copy(alpha = star.alpha),
                radius = star.radius.dp.toPx(),
                center = Offset(stripe.width * star.x, stripe.height * star.y),
            )
        }
        drawPath(sparkle, Color(0xFFF3E9FF))
    }
}

/** A star like the ones in the Nightly icon, [reach] from its middle to each of its four tips. */
private fun sparkle(center: Offset, reach: Float): Path {
    val (x, y) = center
    return Path().apply {
        moveTo(x, y - reach)
        quadraticTo(x, y, x + reach, y)
        quadraticTo(x, y, x, y + reach)
        quadraticTo(x, y, x - reach, y)
        quadraticTo(x, y, x, y - reach)
        close()
    }
}
