package dev.chatter.app.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * The items in groups, at most one group per [windowMs]: the first item at once, and what arrives
 * while a window is open together when it ends. A burst of mentions becomes a few notification
 * updates instead of one per mention, which Android would mostly drop anyway.
 */
fun <T> Flow<T>.inBursts(windowMs: Long): Flow<List<T>> = channelFlow {
    val pending = ArrayList<T>()
    var open = false
    collect { item ->
        val first = synchronized(pending) {
            if (open) {
                pending += item
                false
            } else {
                open = true
                true
            }
        }
        if (!first) return@collect
        send(listOf(item))
        launch {
            while (true) {
                delay(windowMs)
                val burst = synchronized(pending) {
                    if (pending.isEmpty()) {
                        open = false
                        null
                    } else pending.toList().also { pending.clear() }
                } ?: break
                send(burst)
            }
        }
    }
}
