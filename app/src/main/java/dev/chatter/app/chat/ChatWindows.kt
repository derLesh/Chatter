package dev.chatter.app.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * The chat windows on screen, and the channel each one shows.
 *
 * There can be two of them: the app, and a bubble floating over a different app entirely. One flag
 * and one channel cannot tell that story — a bubble opening would make the app behind it look
 * gone, and closing it would leave the app believing the bubble's channel was still in front. So
 * every window says for itself what it is doing, and the answers are added up here.
 *
 * Windows are told apart by identity; each one passes its own view model as the key.
 */
class ChatWindows {
    private val open = ConcurrentHashMap.newKeySet<Any>()
    private val shown = ConcurrentHashMap<Any, String>()

    private val _anyVisible = MutableStateFlow(false)
    /** Whether the user has any window of the app in front of them. */
    val anyVisible: StateFlow<Boolean> = _anyVisible

    /** A window came to the front or left it, showing [channel] for as long as it is there. */
    fun setVisible(window: Any, visible: Boolean, channel: String?) {
        if (visible) open.add(window) else open.remove(window)
        setChannel(window, channel.takeIf { visible })
        _anyVisible.value = open.isNotEmpty()
    }

    /** The channel a window has moved to, or null while it shows none. */
    fun setChannel(window: Any, channel: String?) {
        if (channel == null) shown.remove(window) else shown[window] = channel
    }

    /** Whether this channel is in front of the user, in whichever window. */
    fun isWatching(channel: String): Boolean = shown.containsValue(channel)
}
