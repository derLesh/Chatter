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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first

/**
 * Frames per second for animated emotes. At or above [ACTIVE] the RenderThread animates them at
 * their own pace; below, [SharedEmotes] steps them at the lower rate, which is also requested via
 * `preferredFrameRate`. See [rememberEmoteFrameRate].
 */
object EmoteFrameRate {
    /** Smooth for any emote, a quarter of what a 120 Hz screen would draw. */
    const val ACTIVE = 30f

    /** Still clearly moving, at a third of the frames. */
    const val IDLE = 10f

    /** Idle time before emotes slow down. */
    const val IDLE_AFTER_MS = 3 * 60_000L
}

/**
 * When the chat was last touched. Not state: a touch must not recompose, only wake
 * [rememberEmoteFrameRate] and [screenOnWhileChatMoves].
 */
class ChatActivity {
    @Volatile var last: Long = System.currentTimeMillis()
        private set
    private val touched = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every touch from now on; earlier ones are not replayed. */
    val touches: Flow<Unit> = touched

    fun note() {
        last = System.currentTimeMillis()
        touched.tryEmit(Unit)
    }

    // Touches from before the chat went idle do not end it, so only new ones count.
    internal suspend fun awaitTouch() = touches.first()
}

/**
 * [EmoteFrameRate.ACTIVE], or [EmoteFrameRate.IDLE] once the chat was not touched for
 * [EmoteFrameRate.IDLE_AFTER_MS], e.g. a phone next to the stream. The next touch restores the full
 * rate. Always active when [enabled] is false.
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

/** Notes every touch inside without consuming it. */
fun Modifier.noteTouches(activity: ChatActivity): Modifier = pointerInput(activity) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press) activity.note()
        }
    }
}
