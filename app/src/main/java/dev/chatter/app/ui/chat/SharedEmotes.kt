package dev.chatter.app.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.createBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil3.ImageLoader
import coil3.size.ScaleDrawable
import coil3.asDrawable
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import java.util.IdentityHashMap
import java.util.WeakHashMap

/**
 * One drawable per emote, shared by every place it is on screen at the same time.
 *
 * Coil keeps animated images out of its memory cache, so through AsyncImage every occurrence of
 * an emote decoded it again and ran its animation on a clock of its own. A busy chat is the same
 * few animated emotes many times over; with each of them changing frame at its own moment there
 * was a new frame to draw nearly every vsync, and each one redraws the whole window. Shared, the
 * copies of an emote change frame together and are decoded once.
 *
 * Main thread only, as drawables are.
 *
 * Normally an animated emote is animated by the RenderThread, at the pace its file sets, and
 * nothing the app asks for changes that pace. For fewer frames ([setFrameRate]) the app steps the
 * animations itself: each one is drawn in software into a small picture of its own, still at its
 * own pace — drawn less often, it would not skip frames but play slower — and one tick for all of
 * them puts what changed on screen, a single window frame at the lower rate.
 */
object SharedEmotes {
    /** Recently shown emotes kept once off screen, so scrolling back does not decode them again. */
    private const val KEEP = 64

    private class Shown(val drawable: Drawable) {
        val listeners = LinkedHashSet<() -> Unit>()

        /** While stepped: the current frame, drawn in software. */
        var frame: Bitmap? = null

        /** While stepped: the frame has moved on since the screen last showed it. */
        var changed = false

        /** While stepped: the last draw asked for the next frame. */
        var scheduled = false

        /** While stepped: draws the next frame when the animation says it is due. */
        val advance = Runnable {
            frame?.let {
                drawFrame(this, it)
                changed = true
            }
        }

        private val animated = drawable.isAnimatedImage()
        val stepped: Boolean get() = frameIntervalMs > 0 && animated
    }

    /** Coil hands an animated image over wrapped in a [ScaleDrawable] of its own. */
    private fun Drawable.isAnimatedImage(): Boolean =
        this is AnimatedImageDrawable || (this as? ScaleDrawable)?.child is AnimatedImageDrawable

    /**
     * Per loader, because the static loader (animated emotes turned off) must not be handed an
     * animated drawable the other one loaded.
     */
    private class Store {
        val shown = HashMap<String, Shown>()
        val recent = LruCache<String, Drawable>(KEEP)
    }

    private val stores = WeakHashMap<ImageLoader, Store>()
    private val byDrawable = IdentityHashMap<Drawable, Shown>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** How often stepped animations are put on screen, or 0 while the RenderThread animates them. */
    private var frameIntervalMs = 0L
    private val tick = Runnable { present() }

    /**
     * How many frames a second animated emotes get on screen: [EmoteFrameRate.ACTIVE] or more
     * leaves them to the RenderThread, anything less has them stepped and shown at that rate.
     */
    fun setFrameRate(fps: Float) {
        val interval = if (fps >= EmoteFrameRate.ACTIVE) 0L else (1000f / fps).toLong()
        if (interval == frameIntervalMs) return
        frameIntervalMs = interval
        mainHandler.removeCallbacks(tick)
        byDrawable.forEach { (drawable, shown) ->
            mainHandler.removeCallbacks(shown.advance, drawable)
            // Let go of, not recycled: a frame already drawn may still be on its way to the screen.
            shown.frame = null
            shown.changed = false
            // Drawn once more either way: into its own picture now, or by the RenderThread again.
            shown.listeners.toList().forEach { it() }
        }
    }

    /**
     * The picture to draw for [drawable] while animations are stepped, or null to draw the
     * drawable itself. The first time, this is also where its first frame is drawn.
     */
    fun steppedFrame(drawable: Drawable): Bitmap? {
        val shown = byDrawable[drawable]?.takeIf { it.stepped } ?: return null
        shown.frame?.let { return it }
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val frame = createBitmap(width, height)
        shown.frame = frame
        drawFrame(shown, frame)
        if (!mainHandler.hasCallbacks(tick)) mainHandler.postDelayed(tick, frameIntervalMs)
        return frame
    }

    /**
     * One software draw: shows the frame that is due and, through [fanOut], asks for the next
     * one. Drawn on a software canvas, the drawable steps frame by frame at its own pace instead
     * of handing itself to the RenderThread. It moves one frame per draw and only when that frame
     * is due, so it has to be drawn at its own pace — at a slower one it would play slower.
     */
    private fun drawFrame(shown: Shown, frame: Bitmap) {
        shown.scheduled = false
        frame.eraseColor(Color.TRANSPARENT)
        shown.drawable.draw(Canvas(frame))
        // The drawable measures in nanoseconds and the handler in milliseconds: drawn a hair before
        // its frame is due, it finds less than a millisecond left, says 0, and asks for nothing —
        // which would leave it standing still for good. A running one is simply asked again.
        if (!shown.scheduled && (shown.drawable as? Animatable)?.isRunning == true) {
            mainHandler.postAtTime(shown.advance, shown.drawable, SystemClock.uptimeMillis() + RETRY_MS)
        }
    }

    /** How soon a draw that came too early is tried again. */
    private const val RETRY_MS = 4L

    /**
     * Puts on screen what the stepped animations moved on to since last time, all in one window
     * frame. Decoding a small emote at its own pace costs little; drawing and showing the whole
     * window for each of its frames is what the lower rate saves.
     */
    private fun present() {
        if (frameIntervalMs <= 0) return
        var any = false
        byDrawable.values.forEach { shown ->
            if (shown.frame == null) return@forEach
            any = true
            if (shown.changed) {
                shown.changed = false
                shown.listeners.toList().forEach { it() }
            }
        }
        // Nothing stepped on screen, nothing to wake up for; the next one starts this again.
        if (any) mainHandler.postDelayed(tick, frameIntervalMs)
    }

    private fun store(loader: ImageLoader) = stores.getOrPut(loader) { Store() }

    // A drawable has room for one callback only, so invalidations are passed on to every place
    // that draws it.
    private val fanOut = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            byDrawable[who]?.listeners?.toList()?.forEach { it() }
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            // A stepped animation's next frame is drawn into its own picture when it is due, not
            // put on screen: [present] does that for all of them at the lower rate.
            val shown = byDrawable[who]
            if (shown != null && shown.stepped && shown.frame != null) {
                shown.scheduled = true
                mainHandler.postAtTime(shown.advance, who, `when`)
            }
            else mainHandler.postAtTime(what, who, `when`)
        }

        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            mainHandler.removeCallbacks(what, who)
            byDrawable[who]?.let { mainHandler.removeCallbacks(it.advance, who) }
        }
    }

    fun cached(loader: ImageLoader, url: String): Drawable? {
        val store = store(loader)
        return store.shown[url]?.drawable ?: store.recent.get(url)
    }

    suspend fun load(context: Context, loader: ImageLoader, url: String): Drawable? {
        cached(loader, url)?.let { return it }
        val result = loader.execute(ImageRequest.Builder(context).data(url).build()) as? SuccessResult ?: return null
        // Another occurrence may have finished loading the same emote in the meantime.
        cached(loader, url)?.let { return it }
        val drawable = result.image.asDrawable(context.resources)
        drawable.setBounds(0, 0, drawable.intrinsicWidth.coerceAtLeast(1), drawable.intrinsicHeight.coerceAtLeast(1))
        store(loader).recent.put(url, drawable)
        return drawable
    }

    fun show(loader: ImageLoader, url: String, drawable: Drawable, listener: () -> Unit) {
        val store = store(loader)
        val shown = store.shown.getOrPut(url) { Shown(drawable) }
        // A drawable dropped from the cache while on screen can be loaded a second time; that
        // copy runs on its own, as every copy used to.
        if (shown.drawable !== drawable) return
        shown.listeners += listener
        if (shown.listeners.size == 1) {
            byDrawable[drawable] = shown
            drawable.callback = fanOut
            drawable.setVisible(true, true)
            // Started by the first occurrence only: a later one must not restart the animation
            // the others are in the middle of.
            (drawable as? Animatable)?.start()
        }
    }

    fun hide(loader: ImageLoader, url: String, drawable: Drawable, listener: () -> Unit) {
        val store = store(loader)
        val shown = store.shown[url]?.takeIf { it.drawable === drawable } ?: return
        shown.listeners -= listener
        if (shown.listeners.isEmpty()) {
            store.shown.remove(url)
            byDrawable.remove(drawable)
            mainHandler.removeCallbacks(shown.advance, drawable)
            shown.frame = null
            store.recent.put(url, drawable)
            // AnimatedImageDrawable keeps animating on the RenderThread when nothing draws it.
            (drawable as? Animatable)?.stop()
            drawable.setVisible(false, false)
            drawable.callback = null
        }
    }
}

/**
 * An emote drawn from [SharedEmotes], fitted and centered in [modifier]'s size.
 * [onLoaded] hears the image's size once it is loaded.
 */
@Composable
fun SharedEmoteImage(
    url: String,
    contentDescription: String?,
    loader: ImageLoader,
    modifier: Modifier = Modifier,
    onLoaded: ((width: Int, height: Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    var drawable by remember(url, loader) { mutableStateOf(SharedEmotes.cached(loader, url)) }
    LaunchedEffect(url, loader) {
        if (drawable == null) drawable = SharedEmotes.load(context, loader, url)
    }
    val loaded = drawable
    LaunchedEffect(loaded) {
        if (loaded != null) onLoaded?.invoke(loaded.intrinsicWidth, loaded.intrinsicHeight)
    }
    // Read while drawing, so an invalidation redraws this occurrence without recomposing it.
    var invalidations by remember { mutableIntStateOf(0) }
    if (loaded != null) {
        DisposableEffect(loaded) {
            val listener: () -> Unit = { invalidations++ }
            SharedEmotes.show(loader, url, loaded, listener)
            onDispose { SharedEmotes.hide(loader, url, loaded, listener) }
        }
    }
    Spacer(
        modifier
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .drawBehind {
                invalidations
                loaded?.let { drawable -> drawFitted(drawable, SharedEmotes.steppedFrame(drawable)) }
            },
    )
}

/**
 * Scaled on the canvas instead of through the bounds: every occurrence shares the drawable, and
 * bounds set for one would move the others. [frame] is the drawable's current frame while
 * animations are stepped (see [SharedEmotes.setFrameRate]), drawn in its place.
 */
private fun DrawScope.drawFitted(drawable: Drawable, frame: Bitmap?) {
    val width = drawable.bounds.width().toFloat()
    val height = drawable.bounds.height().toFloat()
    if (width <= 0f || height <= 0f) return
    val scale = minOf(size.width / width, size.height / height)
    withTransform({
        translate((size.width - width * scale) / 2f, (size.height - height * scale) / 2f)
        scale(scale, scale, pivot = Offset.Zero)
    }) {
        drawIntoCanvas {
            if (frame != null) it.nativeCanvas.drawBitmap(frame, null, drawable.bounds, framePaint)
            else drawable.draw(it.nativeCanvas)
        }
    }
}

/** Scaled like the drawable itself would be. */
private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)
