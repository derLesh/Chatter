package dev.chatter.app.ui.chat

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import coil3.compose.AsyncImagePainter
import dev.chatter.app.emotes.Emote
import java.util.concurrent.ConcurrentHashMap

/**
 * Aspect ratios measured from loaded images, for emotes whose provider does not report a size
 * (BTTV). Compose state, so rows showing such an emote re-layout once the real width is known.
 */
object EmoteSizes {
    /**
     * One piece of state per emote, rather than one map holding all of them. A snapshot state map
     * is a single piece of state however many keys it has, so measuring one emote would redraw
     * every row on screen that is still waiting on any other.
     */
    private val measured = ConcurrentHashMap<String, MutableState<Float?>>()

    private fun slot(url: String): MutableState<Float?> =
        measured.computeIfAbsent(url) { mutableStateOf(null) }

    fun aspectRatio(emote: Emote): Float =
        if (emote.sizeKnown) emote.aspectRatio else slot(emote.url).value ?: emote.aspectRatio

    /** Pass as `onSuccess` of the emote's AsyncImage. */
    fun onLoaded(emote: Emote): ((AsyncImagePainter.State.Success) -> Unit)? {
        if (emote.sizeKnown) return null
        val slot = slot(emote.url)
        if (slot.value != null) return null
        return { state ->
            val image = state.result.image
            if (image.width > 0 && image.height > 0) slot.value = image.width.toFloat() / image.height
        }
    }
}
