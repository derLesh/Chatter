package dev.chatter.app.ui.chat

import android.content.Context
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
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
import coil3.ImageLoader
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
 */
object SharedEmotes {
    /** Recently shown emotes kept once off screen, so scrolling back does not decode them again. */
    private const val KEEP = 64

    private class Shown(val drawable: Drawable) {
        val listeners = LinkedHashSet<() -> Unit>()
    }

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

    private fun store(loader: ImageLoader) = stores.getOrPut(loader) { Store() }

    // A drawable has room for one callback only, so invalidations are passed on to every place
    // that draws it.
    private val fanOut = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            byDrawable[who]?.listeners?.toList()?.forEach { it() }
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            mainHandler.postAtTime(what, who, `when`)
        }

        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            mainHandler.removeCallbacks(what, who)
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
                loaded?.let { drawFitted(it) }
            },
    )
}

/**
 * Scaled on the canvas instead of through the bounds: every occurrence shares the drawable, and
 * bounds set for one would move the others.
 */
private fun DrawScope.drawFitted(drawable: Drawable) {
    val width = drawable.bounds.width().toFloat()
    val height = drawable.bounds.height().toFloat()
    if (width <= 0f || height <= 0f) return
    val scale = minOf(size.width / width, size.height / height)
    withTransform({
        translate((size.width - width * scale) / 2f, (size.height - height * scale) / 2f)
        scale(scale, scale, pivot = Offset.Zero)
    }) {
        drawIntoCanvas { drawable.draw(it.nativeCanvas) }
    }
}
