package dev.chatter.app.irc

/**
 * Decides which channels to JOIN and when.
 *
 * Twitch allows [limit] JOINs per [windowMs] for a normal account and silently ignores the rest, so
 * the channels stay empty. Joins go out in batches within the limit, most wanted channels first.
 *
 * Twitch confirms a JOIN with a ROOMSTATE. A channel without one after [answerTimeoutMs] is joined
 * again, with the wait doubling each time (a suspended channel never answers).
 *
 * Not thread-safe; [IrcConnection] calls it under its lock. The clock is injected for tests.
 */
internal class JoinQueue(
    private val clock: () -> Long,
    /** Lower goes first: the channel on screen, then notifying ones, then the rest. */
    private val rank: (String) -> Int = { 0 },
    private val limit: Int = 20,
    private val windowMs: Long = 10_000L,
    private val answerTimeoutMs: Long = 30_000L,
) {
    /** Send times of the JOINs in the current window, oldest first. */
    private val sent = ArrayDeque<Long>()

    /** Channels still to send, in request order. */
    private val waiting = LinkedHashSet<String>()

    /** Sent but unconfirmed channels: when the next attempt is due, and how many there were. */
    private val unanswered = HashMap<String, Pending>()

    private class Pending(val dueAt: Long, val tries: Int)

    /** Queues [channel]. Nothing happens if it is already waiting or sent. */
    fun add(channel: String) {
        if (channel !in unanswered) waiting.add(channel)
    }

    /** Drops [channel] from the queue and the retries. */
    fun remove(channel: String) {
        waiting.remove(channel)
        unanswered.remove(channel)
    }

    /** Twitch confirmed the JOIN of [channel]. */
    fun answered(channel: String) {
        unanswered.remove(channel)
    }

    /**
     * A new connection: all [channels] wait again. JOINs sent on the old connection still count;
     * the limit is per account, not per socket.
     */
    fun restart(channels: Collection<String>) {
        waiting.clear()
        unanswered.clear()
        waiting.addAll(channels)
    }

    /** Channels to JOIN now, best first; empty while the limit is used up. */
    fun take(): List<String> {
        val now = clock()
        while (sent.isNotEmpty() && now - sent.first() >= windowMs) sent.removeFirst()
        val overdue = unanswered.filterValues { it.dueAt <= now }.keys
        val budget = limit - sent.size
        if (budget <= 0) return emptyList()
        val candidates = (waiting + overdue).sortedBy(rank)
        val batch = candidates.take(budget)
        for (channel in batch) {
            val tries = (unanswered[channel]?.tries ?: 0) + 1
            waiting.remove(channel)
            unanswered[channel] = Pending(now + (answerTimeoutMs shl (tries - 1).coerceAtMost(MAX_DOUBLINGS)), tries)
            sent.addLast(now)
        }
        return batch
    }

    /** When [take] may return something again, or null if nothing is pending. */
    fun nextDueAt(): Long? {
        val freed = if (sent.size >= limit) sent.first() + windowMs else null
        val waitingNow = if (waiting.isNotEmpty()) (freed ?: clock()) else null
        val retry = unanswered.values.minOfOrNull { it.dueAt }?.let { maxOf(it, freed ?: it) }
        return listOfNotNull(waitingNow, retry).minOrNull()
    }

    private companion object {
        /** 30 s doubled five times: an unanswered channel is retried every 16 minutes at most. */
        const val MAX_DOUBLINGS = 5
    }
}
