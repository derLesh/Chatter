package dev.chatter.app.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * The chat windows on screen, and the channels each one shows.
 *
 * There can be two of them: the app, and a bubble floating over a different app entirely. One flag
 * and one channel cannot tell that story — a bubble opening would make the app behind it look
 * gone, and closing it would leave the app believing the bubble's channel was still in front. So
 * every window says for itself what it is doing, and the answers are added up here.
 *
 * Windows are told apart by identity; each one passes its own view model as the key. A window
 * usually shows one channel, and several while it is on a combined chat.
 */
class ChatWindows {
    private val open = ConcurrentHashMap.newKeySet<Any>()
    private val shown = ConcurrentHashMap<Any, Set<String>>()

    private val _anyVisible = MutableStateFlow(false)
    /** Whether the user has any window of the app in front of them. */
    val anyVisible: StateFlow<Boolean> = _anyVisible

    /** A window came to the front or left it, showing [channels] for as long as it is there. */
    fun setVisible(window: Any, visible: Boolean, channels: Set<String>) {
        if (visible) open.add(window) else open.remove(window)
        setChannels(window, if (visible) channels else emptySet())
        _anyVisible.value = open.isNotEmpty()
    }

    /** The channels a window has moved to; none while it shows no chat. */
    fun setChannels(window: Any, channels: Set<String>) {
        if (channels.isEmpty()) shown.remove(window) else shown[window] = channels
    }

    /** Whether this channel is in front of the user, in whichever window. */
    fun isWatching(channel: String): Boolean = shown.values.any { channel in it }

    /** True while the whisper tab of the inbox is the thing in front of the user. */
    val whispersVisible = MutableStateFlow(false)

    /** Whether a whisper arriving now would land under the user's eyes. */
    fun isWatchingWhispers(): Boolean = _anyVisible.value && whispersVisible.value
}
