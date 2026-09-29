package dev.chatter.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/**
 * How many frames a second animated emotes are put on screen with. Above [ACTIVE] and at it, the
 * RenderThread animates them at the pace their files set; below it, [SharedEmotes] steps them and
 * puts them on screen at the lower rate, which is also what they ask the display for through
 * `preferredFrameRate`. See [rememberEmoteFrameRate] for when it is lowered.
 */
object EmoteFrameRate {
    /** Smooth enough for any emote, and a quarter of what a 120 Hz screen would otherwise draw. */
    const val ACTIVE = 30f

    /** Still clearly moving, at a third of the frames. */
    const val IDLE = 10f

    /** How long the chat goes untouched before its emotes slow down. */
    const val IDLE_AFTER_MS = 3 * 60_000L
}

/**
 * When the chat was last touched or typed into. A plain holder rather than state: a touch must
 * not recompose the screen, only wake up [rememberEmoteFrameRate] if it is waiting for one.
 */
class ChatActivity {
    @Volatile var last: Long = System.currentTimeMillis()
        private set
    private val touched = Channel<Unit>(Channel.CONFLATED)

    fun note() {
        last = System.currentTimeMillis()
        touched.trySend(Unit)
    }

    internal suspend fun awaitTouch() {
        // A touch from before the chat went idle is not one that ends it.
        while (touched.tryReceive().isSuccess) Unit
        touched.receive()
    }
}

/**
 * The frame rate for the chat's emotes: [EmoteFrameRate.ACTIVE], and [EmoteFrameRate.IDLE] once
 * nobody has touched the chat for [EmoteFrameRate.IDLE_AFTER_MS] — a phone next to the stream, the
 * screen kept on, where nobody looks closely at a GIF that went by a minute ago. The next touch
 * brings the full rate back at once. Off, when [enabled] is.
 */
@Composable
fun rememberEmoteFrameRate(activity: ChatActivity, enabled: Boolean): Float {
    var idle by remember { mutableStateOf(false) }
    LaunchedEffect(activity, enabled) {
        idle = false
        if (!enabled) return@LaunchedEffect
        while (true) {
            val quiet = System.currentTimeMillis() - activity.last
            if (quiet < EmoteFrameRate.IDLE_AFTER_MS) {
                delay(EmoteFrameRate.IDLE_AFTER_MS - quiet)
                continue
            }
            idle = true
            activity.awaitTouch()
            idle = false
        }
    }
    return if (idle) EmoteFrameRate.IDLE else EmoteFrameRate.ACTIVE
}

/** Notes every touch anywhere inside, without taking it from whatever it was meant for. */
fun Modifier.noteTouches(activity: ChatActivity): Modifier = pointerInput(activity) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press) activity.note()
        }
    }
}
