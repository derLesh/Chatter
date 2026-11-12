package me.lesh.chatter.ui.chat

import android.graphics.BlendMode
import android.graphics.ComposeShader
import android.graphics.Matrix
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.lesh.chatter.badges.NamePaint
import me.lesh.chatter.ui.theme.chatColor
import me.lesh.chatter.ui.theme.maxLumaOnLight
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * How a painted name is drawn: with [brush], or in the paint's main color while an image paint's
 * picture is still loading ([brush] null).
 */
internal fun namePaintStyle(paint: NamePaint, brush: NamePaintBrush?, density: Density, style: ChatStyle): SpanStyle {
    // Paint shadows are dark glows made for a dark chat; on a light one they only smudge the name.
    val shadow = paint.shadows.firstOrNull()?.takeIf { style.dark }?.let {
        Shadow(
            color = Color(it.color),
            offset = Offset(it.x * density.density, it.y * density.density),
            blurRadius = it.radius * density.density,
        )
    }
    return if (brush != null) SpanStyle(brush = brush, shadow = shadow)
    else SpanStyle(color = paint.fallbackColor?.let { chatColor(it, style.dark, style.textBackground) } ?: Color.Unspecified, shadow = shadow)
}

/** Where the characters [range] were laid out, or null if they are not in this layout. */
internal fun TextLayoutResult.boundsOf(range: IntRange): Rect? {
    if (range.isEmpty() || range.last >= layoutInput.text.length) return null
    val first = getBoundingBox(range.first)
    val last = getBoundingBox(range.last)
    return Rect(
        left = minOf(first.left, last.left),
        top = minOf(first.top, last.top),
        right = max(first.right, last.right),
        bottom = max(first.bottom, last.bottom),
    )
}

/** Largest side of a paint's picture, in pixels. */
private const val PAINT_IMAGE_MAX_SIDE = 512

/** An image paint's picture, as a bitmap a shader can use, and the highest red, green and blue in it. */
internal class PaintImage(val bitmap: ImageBitmap, val brightest: Color)

/** An image paint's picture; null until it has loaded. */
@Composable
internal fun rememberPaintImage(url: String, loader: ImageLoader): PaintImage? {
    val context = LocalContext.current
    val image by produceState<PaintImage?>(null, url, loader) {
        val request = ImageRequest.Builder(context)
            .data(url)
            // Shaders cannot read hardware bitmaps.
            .allowHardware(false)
            // Stretched over a name either way; only ever scaled down.
            .size(PAINT_IMAGE_MAX_SIDE)
            .precision(Precision.INEXACT)
            .build()
        val bitmap = (loader.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            PaintImage(bitmap.asImageBitmap(), brightestChannels(pixels))
        }
    }
    return image
}

/** The highest red, green and blue among [pixels] (ARGB) that are more opaque than clear; black if none is. */
internal fun brightestChannels(pixels: IntArray): Color {
    var red = 0
    var green = 0
    var blue = 0
    for (pixel in pixels) {
        if ((pixel ushr 24) < 128) continue
        red = max(red, (pixel shr 16) and 0xFF)
        green = max(green, (pixel shr 8) and 0xFF)
        blue = max(blue, pixel and 0xFF)
    }
    return Color(red, green, blue)
}

/**
 * What to multiply an image paint's colors by on a light theme so no pixel is lighter than a readable
 * name ([maxLuma]); 1 when none is. Every pixel stays at or below the [brightest] channels in each
 * channel, dimmed too, so bounding that one color bounds them all.
 */
internal fun paintImageDimming(brightest: Color, maxLuma: Float): Float {
    if (brightest.luminance() <= maxLuma) return 1f
    var lo = 0f
    var hi = 1f
    repeat(DIMMING_STEPS) {
        val mid = (lo + hi) / 2f
        if (Color(brightest.red * mid, brightest.green * mid, brightest.blue * mid).luminance() <= maxLuma) lo = mid else hi = mid
    }
    // Down to a step the shader's 8-bit grey holds exactly; rounding up would let it reach past maxLuma.
    return floor(lo * 255f) / 255f
}

private const val DIMMING_STEPS = 16

/**
 * A paint over the name alone. Compose sizes a span's brush to the whole paragraph, so the name's
 * bounds come from [place], called in onTextLayout. That runs before the line is drawn and the shader
 * is made, so the line is composed and laid out once. One per line: it keeps its identity, so the
 * text stays equal and is not laid out again.
 */
internal class NamePaintBrush(private val paint: NamePaint, private val image: PaintImage?, private val style: ChatStyle) : ShaderBrush() {
    private var bounds: Rect? = null
    private var shader: Shader? = null
    private var shaderBounds: Rect? = null
    private val shaderMatrix = Matrix()

    fun place(nameBounds: Rect?) {
        bounds = nameBounds
        val made = shader ?: return
        val madeFor = shaderBounds ?: return
        if (nameBounds == null || nameBounds == madeFor) return
        // Compose makes the shader again only when the paragraph's size changes, but the name can
        // move without that, e.g. when an emote before it gets its real width.
        made.setLocalMatrix(Matrix(shaderMatrix).apply { postTranslate(nameBounds.left - madeFor.left, nameBounds.top - madeFor.top) })
    }

    override fun createShader(size: Size): Shader {
        val placed = bounds?.takeIf { it.width > 0f && it.height > 0f }
        val made = when {
            placed == null -> solid()
            paint.kind == NamePaint.Kind.Linear -> linear(placed)
            paint.kind == NamePaint.Kind.Radial -> radial(placed)
            else -> image?.let { picture(it, placed) } ?: solid()
        }
        shader = made
        shaderBounds = placed
        made.getLocalMatrix(shaderMatrix)
        return made
    }

    /** For a line drawn before it was placed, which should not happen. */
    private fun solid(): Shader {
        val color = (paint.fallbackColor ?: paint.stops.firstOrNull()?.color)?.let { chatColor(it, style.dark, style.textBackground) }
            ?: if (style.dark) Color.White else Color.Black
        return LinearGradientShader(Offset.Zero, Offset(1f, 0f), listOf(color, color))
    }

    private val colors get() = paint.stops.map { chatColor(it.color, style.dark, style.textBackground) }

    private val tileMode get() = if (paint.repeat) TileMode.Repeated else TileMode.Clamp

    /** Where the stops sit within the share [from] to [to] of the gradient's length. */
    private class Stretch(val positions: List<Float>, val from: Float, val to: Float)

    /** A repeating gradient repeats from its first stop to its last, not its whole length. */
    private fun stretch(): Stretch {
        if (!paint.repeat) return Stretch(paint.stops.map { it.at }, 0f, 1f)
        val from = paint.stops.first().at
        val to = paint.stops.last().at.coerceAtLeast(from + 0.01f)
        return Stretch(paint.stops.map { (it.at - from) / (to - from) }, from, to)
    }

    /** CSS's linear-gradient: through the centre at [NamePaint.angle], reaching both corners. */
    private fun linear(bounds: Rect): Shader {
        val radians = Math.toRadians(paint.angle.toDouble())
        val direction = Offset(sin(radians).toFloat(), -cos(radians).toFloat())
        val length = abs(bounds.width * direction.x) + abs(bounds.height * direction.y)
        val start = bounds.center - direction * (length / 2)
        val stretch = stretch()
        return LinearGradientShader(
            from = start + direction * (length * stretch.from),
            to = start + direction * (length * stretch.to),
            colors = colors,
            colorStops = stretch.positions,
            tileMode = tileMode,
        )
    }

    /** CSS's radial-gradient from the centre out to the farthest corner. */
    private fun radial(bounds: Rect): Shader {
        val stretch = stretch()
        val radius = (hypot(bounds.width / 2, bounds.height / 2) * stretch.to).coerceAtLeast(1f)
        return RadialGradientShader(bounds.center, radius, colors, stretch.positions, tileMode)
    }

    /**
     * The picture covering the name, like CSS's background-size: cover. Only part of it shows, so on
     * a light theme the whole of it is dimmed until even its lightest pixel reads, as a gradient's
     * every color does.
     */
    private fun picture(image: PaintImage, bounds: Rect): Shader {
        val bitmap = image.bitmap
        val scale = max(bounds.width / bitmap.width, bounds.height / bitmap.height)
        val picture = ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated).apply {
            setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate(bounds.left, bounds.top)
            })
        }
        if (style.dark) return picture
        val dimming = paintImageDimming(image.brightest, maxLumaOnLight(style.textBackground))
        if (dimming >= 1f) return picture
        val grey = Color(dimming, dimming, dimming)
        return ComposeShader(picture, LinearGradientShader(Offset.Zero, Offset(1f, 0f), listOf(grey, grey)), BlendMode.MODULATE)
    }
}
