package dev.chatter.app.net

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Says, once, when something outside the app cannot be reached.
 *
 * Chatter leans on half a dozen services nobody here runs, and when one of them goes away it used
 * to do so quietly: a line in the log, and badges or the history simply missing. This is the other
 * end of that — one word on the screen, and then silence, however often the thing is asked again.
 *
 * Once per service per run, because the app asks some of them on every return to the foreground
 * and a provider that is down stays down for a while. A service that answers again may be
 * complained about again later, by way of [reachable].
 */
class ServiceTrouble {
    private val told = ConcurrentHashMap.newKeySet<String>()

    private val _unreachable = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** The name of a service, as a person would call it, the first time it does not answer. */
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
