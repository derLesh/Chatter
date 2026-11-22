package me.lesh.chatter.util

/**
 * Sliding-window limiter: at most `limit` events per [windowMs]. Asking ([hasRoom]) and counting
 * ([record]) are apart, so an event that did not happen after all costs nothing. Not thread-safe.
 */
class RateLimiter(private val windowMs: Long) {
    private val sent = ArrayDeque<Long>()

    fun hasRoom(now: Long, limit: Int): Boolean {
        while (sent.isNotEmpty() && now - sent.first() >= windowMs) sent.removeFirst()
        return sent.size < limit
    }

    fun record(now: Long) {
        sent.addLast(now)
    }
}
