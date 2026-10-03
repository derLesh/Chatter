package dev.chatter.app.net

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Reports once per run when an outside service cannot be reached, so missing badges or history get
 * one line on screen instead of none. A service that answers again may be reported again; see
 * [reachable].
 */
class ServiceTrouble {
    private val told = ConcurrentHashMap.newKeySet<String>()

    private val _unreachable = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** The display name of a service the first time it does not answer. */
    val unreachable: SharedFlow<String> = _unreachable

    fun report(service: String) {
        if (told.add(service)) _unreachable.tryEmit(service)
    }

    fun reachable(service: String) {
        told.remove(service)
    }

    companion object {
        const val TWITCH = "Twitch"
        const val CHATTERINO = "Chatterino"
        const val SUPPORTERS = "GitHub"
    }
}
