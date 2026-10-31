package me.lesh.chatter.ui.chat

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
import me.lesh.chatter.badges.NamePaint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * How a painted name is drawn: with [brush], or in the paint's main color while an image paint's
 * picture is still loading ([brush] null).
 */
internal fun namePaintStyle(paint: NamePaint, brush: NamePaintBrush?, density: Density): SpanStyle {
    val shadow = paint.shadows.firstOrNull()?.let {
        Shadow(
            color = Color(it.color),
            offset = Offset(it.x * density.density, it.y * density.density),
            blurRadius = it.radius * density.density,
        )
    }
    return if (brush != null) SpanStyle(brush = brush, shadow = shadow)
    else SpanStyle(color = paint.fallbackColor?.let { Color(it) } ?: Color.Unspecified, shadow = shadow)
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

/** An image paint's picture, as a bitmap a shader can use; null until it has loaded. */
@Composable
internal fun rememberPaintImage(url: String, loader: ImageLoader): ImageBitmap? {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, url, loader) {
        val request = ImageRequest.Builder(context)
            .data(url)
            // Shaders cannot read hardware bitmaps.
            .allowHardware(false)
            // Stretched over a name either way; only ever scaled down.
            .size(PAINT_IMAGE_MAX_SIDE)
            .precision(Precision.INEXACT)
            .build()
        value = (loader.execute(request) as? SuccessResult)?.image?.toBitmap()?.asImageBitmap()
    }
    return image
}

/**
 * A paint over the name alone. Compose sizes a span's brush to the whole paragraph, so the name's
 * bounds come from [place], called in onTextLayout. That runs before the line is drawn and the shader
 * is made, so the line is composed and laid out once. One per line: it keeps its identity, so the
 * text stays equal and is not laid out again.
 */
internal class NamePaintBrush(private val paint: NamePaint, private val image: ImageBitmap?) : ShaderBrush() {
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
        val color = Color(paint.fallbackColor ?: paint.stops.firstOrNull()?.color ?: Color.White.toArgb())
        return LinearGradientShader(Offset.Zero, Offset(1f, 0f), listOf(color, color))
    }

    private val colors get() = paint.stops.map { Color(it.color) }

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

    /** The picture covering the name, like CSS's background-size: cover. */
    private fun picture(image: ImageBitmap, bounds: Rect): Shader {
        val scale = max(bounds.width / image.width, bounds.height / image.height)
        return ImageShader(image, TileMode.Repeated, TileMode.Repeated).apply {
            setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate(bounds.left, bounds.top)
            })
        }
    }
}
