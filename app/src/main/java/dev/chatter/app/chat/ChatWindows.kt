package dev.chatter.app.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * The chat windows on screen and the channels each shows.
 *
 * There can be two: the app and a bubble over another app. A single flag could not tell them apart,
 * so each window reports for itself and the answers are combined here. Windows are keyed by
 * identity (their view model). A window shows several channels while on a combined chat.
 */
class ChatWindows {
    private val open = ConcurrentHashMap.newKeySet<Any>()
    private val shown = ConcurrentHashMap<Any, Set<String>>()

    private val _anyVisible = MutableStateFlow(false)
    /** Whether any window of the app is in front. */
    val anyVisible: StateFlow<Boolean> = _anyVisible

    /** A window came to the front or left it, showing [channels] while there. */
    fun setVisible(window: Any, visible: Boolean, channels: Set<String>) {
        if (visible) open.add(window) else open.remove(window)
        setChannels(window, if (visible) channels else emptySet())
        _anyVisible.value = open.isNotEmpty()
    }

    /** The channels a window shows now; empty while it shows no chat. */
    fun setChannels(window: Any, channels: Set<String>) {
        if (channels.isEmpty()) shown.remove(window) else shown[window] = channels
    }

    /** Whether [channel] is on screen in any window. */
    fun isWatching(channel: String): Boolean = shown.values.any { channel in it }

    /** True while the inbox's whisper tab is in front. */
    val whispersVisible = MutableStateFlow(false)

    /** Whether a whisper arriving now would be seen right away. */
    fun isWatchingWhispers(): Boolean = _anyVisible.value && whispersVisible.value
}
