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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.graphics.createBitmap
import coil3.ImageLoader
import coil3.asDrawable
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.ScaleDrawable
import java.util.IdentityHashMap
import java.util.WeakHashMap

/**
 * One drawable per emote, shared by every place it is shown at the same time.
 *
 * Coil does not memory-cache animated images, so each AsyncImage decoded the emote again and ran
 * its own animation clock. A busy chat shows the same few emotes many times; with each copy
 * changing frames at its own moment almost every vsync had a new frame, redrawing the whole window.
 * Shared copies change frames together and are decoded once.
 *
 * Main thread only, like drawables.
 *
 * Normally the RenderThread animates an emote at its file's pace, which the app cannot change. For
 * a lower rate ([setFrameRate]) the app steps the animations itself: each is drawn in software into
 * a small bitmap at its own pace (drawn less often it would play slower, not skip), and one shared
 * tick puts the changes on screen as a single window frame at the lower rate.
 */
object SharedEmotes {
    /** Recently shown emotes kept off screen, so scrolling back does not decode them again. */
    private const val KEEP = 64

    private class Shown(val drawable: Drawable) {
        val listeners = ArrayList<() -> Unit>(2)

        /**
         * Tells every place to redraw, once per animation frame. By index instead of over a copy:
         * a listener only bumps a counter, so the list cannot change meanwhile.
         */
        fun invalidate() {
            for (i in listeners.indices) listeners[i]()
        }

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

    /** Coil wraps animated images in a [ScaleDrawable]. */
    private fun Drawable.isAnimatedImage(): Boolean =
        this is AnimatedImageDrawable || (this as? ScaleDrawable)?.child is AnimatedImageDrawable

    /**
     * Per loader: the static loader (animations off) must not get an animated drawable from the
     * other.
     */
    private class Store {
        val shown = HashMap<String, Shown>()
        val recent = LruCache<String, Drawable>(KEEP)
    }

    private val stores = WeakHashMap<ImageLoader, Store>()
    private val byDrawable = IdentityHashMap<Drawable, Shown>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** How often stepped animations are shown, or 0 while the RenderThread animates them. */
    private var frameIntervalMs = 0L
    private val tick = Runnable { present() }

    /**
     * Frames per second for animated emotes: [EmoteFrameRate.ACTIVE] or more leaves them to the
     * RenderThread, less steps them at that rate.
     */
    fun setFrameRate(fps: Float) {
        val interval = if (fps >= EmoteFrameRate.ACTIVE) 0L else (1000f / fps).toLong()
        if (interval == frameIntervalMs) return
        frameIntervalMs = interval
        mainHandler.removeCallbacks(tick)
        byDrawable.forEach { (drawable, shown) ->
            mainHandler.removeCallbacks(shown.advance, drawable)
            // Dropped, not recycled: a frame may still be on its way to the screen.
            shown.frame = null
            shown.changed = false
            // Drawn once more either way: into its bitmap, or by the RenderThread again.
            shown.invalidate()
        }
    }

    /**
     * The bitmap to draw for [drawable] while stepping, or null to draw the drawable itself. Draws
     * the first frame on first call.
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
     * One software draw: shows the frame that is due and asks for the next one via [fanOut]. On a
     * software canvas the drawable advances one frame per draw, only when it is due, so it must be
     * drawn at its own pace or it plays slower.
     */
    private fun drawFrame(shown: Shown, frame: Bitmap) {
        shown.scheduled = false
        frame.eraseColor(Color.TRANSPARENT)
        shown.drawable.draw(Canvas(frame))
        // The drawable counts nanoseconds and the handler milliseconds: drawn just before its frame
        // is due, it computes 0 ms, schedules nothing and would stop. A running one is asked again.
        if (!shown.scheduled && (shown.drawable as? Animatable)?.isRunning == true) {
            mainHandler.postAtTime(shown.advance, shown.drawable, SystemClock.uptimeMillis() + RETRY_MS)
        }
    }

    /** Retry delay for a draw that came too early. */
    private const val RETRY_MS = 4L

    /**
     * Shows what the stepped animations advanced to, in one window frame. Decoding small emotes at
     * their own pace is cheap; drawing the window for each of their frames is what the lower rate
     * saves.
     */
    private fun present() {
        if (frameIntervalMs <= 0) return
        var any = false
        byDrawable.values.forEach { shown ->
            if (shown.frame == null) return@forEach
            any = true
            if (shown.changed) {
                shown.changed = false
                shown.invalidate()
            }
        }
        // Nothing stepped on screen: no tick until the next one starts.
        if (any) mainHandler.postDelayed(tick, frameIntervalMs)
    }

    private fun store(loader: ImageLoader) = stores.getOrPut(loader) { Store() }

    // A drawable has a single callback, so invalidations are forwarded to every place drawing it.
    private val fanOut = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            byDrawable[who]?.invalidate()
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            // A stepped animation's next frame goes into its bitmap when due; [present] shows them
            // all at the lower rate.
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
        // Another occurrence may have loaded the same emote meanwhile.
        cached(loader, url)?.let { return it }
        val drawable = result.image.asDrawable(context.resources)
        drawable.setBounds(0, 0, drawable.intrinsicWidth.coerceAtLeast(1), drawable.intrinsicHeight.coerceAtLeast(1))
        store(loader).recent.put(url, drawable)
        return drawable
    }

    fun show(loader: ImageLoader, url: String, drawable: Drawable, listener: () -> Unit) {
        val store = store(loader)
        val shown = store.shown.getOrPut(url) { Shown(drawable) }
        // A drawable dropped from the cache while on screen can be loaded again; that copy animates
        // on its own.
        if (shown.drawable !== drawable) return
        shown.listeners += listener
        if (shown.listeners.size == 1) {
            byDrawable[drawable] = shown
            drawable.callback = fanOut
            drawable.setVisible(true, true)
            // Only the first occurrence starts it; a later one must not restart the animation.
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
 * An emote from [SharedEmotes], fitted and centered in [modifier]'s size. [onLoaded] receives the
 * image size once loaded.
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
    // Read while drawing, so an invalidation redraws without recomposing.
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
 * Scaled on the canvas instead of via bounds: the drawable is shared, and bounds set for one place
 * would move the others. [frame] is the current frame while stepping (see
 * [SharedEmotes.setFrameRate]), drawn instead.
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

/** Filtered like the drawable would be. */
private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)
