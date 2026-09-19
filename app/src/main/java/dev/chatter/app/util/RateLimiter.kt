package dev.chatter.app.util

/** Sliding-window limiter: at most `limit` events per `windowMs`. Not thread-safe. */
class RateLimiter(private val windowMs: Long) {
    private val sent = ArrayDeque<Long>()

    fun tryAcquire(now: Long, limit: Int): Boolean {
        while (sent.isNotEmpty() && now - sent.first() >= windowMs) sent.removeFirst()
        if (sent.size >= limit) return false
        sent.addLast(now)
        return true
    }
}
