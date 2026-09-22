package dev.chatter.app.ui.chat

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import coil3.compose.AsyncImagePainter
import dev.chatter.app.emotes.Emote

/**
 * Aspect ratios measured from loaded images, for emotes whose provider does not report a size
 * (BTTV). Compose state, so rows showing such an emote re-layout once the real width is known.
 */
object EmoteSizes {
    /** Beyond this many emotes the least recently drawn one is dropped and measured again later. */
    private const val KEEP = 512

    /**
     * One piece of state per emote, rather than one map holding all of them. A snapshot state map
     * is a single piece of state however many keys it has, so measuring one emote would redraw
     * every row on screen that is still waiting on any other.
     *
     * Access ordered and bounded: someone who reads chat all day walks past far more emotes than
     * are ever on screen, and none of them would otherwise be let go of again.
     */
    private val measured = object : LinkedHashMap<String, MutableState<Float?>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, MutableState<Float?>>) = size > KEEP
    }

    /** Both reads and writes reorder the map, so every access is behind the same lock. */
    private fun slot(url: String): MutableState<Float?> =
        synchronized(measured) { measured.getOrPut(url) { mutableStateOf(null) } }

    fun aspectRatio(emote: Emote): Float =
        if (emote.sizeKnown) emote.aspectRatio else slot(emote.url).value ?: emote.aspectRatio

    /** Pass as `onSuccess` of the emote's AsyncImage. */
    fun onLoaded(emote: Emote): ((AsyncImagePainter.State.Success) -> Unit)? =
        onSize(emote)?.let { measure -> { state -> measure(state.result.image.width, state.result.image.height) } }

    /** Pass as `onLoaded` of the emote's [SharedEmoteImage]. */
    fun onSize(emote: Emote): ((width: Int, height: Int) -> Unit)? {
        if (emote.sizeKnown) return null
        val slot = slot(emote.url)
        if (slot.value != null) return null
        return { width, height ->
            if (width > 0 && height > 0) slot.value = width.toFloat() / height
        }
    }
}
