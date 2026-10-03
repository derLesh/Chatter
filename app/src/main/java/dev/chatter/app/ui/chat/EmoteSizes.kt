package dev.chatter.app.ui.chat

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import coil3.compose.AsyncImagePainter
import dev.chatter.app.emotes.Emote

/**
 * Aspect ratios measured from loaded images, for emotes without a reported size (BTTV). Compose
 * state, so rows relayout once the width is known.
 */
object EmoteSizes {
    /** Beyond this the least recently drawn emote is dropped and measured again later. */
    private const val KEEP = 512

    /**
     * One state per emote rather than a snapshot state map, which is a single state: measuring one
     * emote would redraw every row waiting on any other. Access-ordered and bounded, since a day of
     * chat shows far more emotes than are ever on screen.
     */
    private val measured = object : LinkedHashMap<String, MutableState<Float?>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, MutableState<Float?>>) = size > KEEP
    }

    /** Reads and writes reorder the map, so every access takes the lock. */
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
