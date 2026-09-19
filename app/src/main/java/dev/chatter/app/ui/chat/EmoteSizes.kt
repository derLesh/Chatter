package dev.chatter.app.ui.chat

import androidx.compose.runtime.mutableStateMapOf
import coil3.compose.AsyncImagePainter
import dev.chatter.app.emotes.Emote

/**
 * Aspect ratios measured from loaded images, for emotes whose provider does not report a size
 * (BTTV). Compose state, so rows showing such an emote re-layout once the real width is known.
 */
object EmoteSizes {
    private val measured = mutableStateMapOf<String, Float>()

    fun aspectRatio(emote: Emote): Float =
        if (emote.sizeKnown) emote.aspectRatio else measured[emote.url] ?: emote.aspectRatio

    /** Pass as `onSuccess` of the emote's AsyncImage. */
    fun onLoaded(emote: Emote): ((AsyncImagePainter.State.Success) -> Unit)? {
        if (emote.sizeKnown || emote.url in measured) return null
        return { state ->
            val image = state.result.image
            if (image.width > 0 && image.height > 0) measured[emote.url] = image.width.toFloat() / image.height
        }
    }
}
