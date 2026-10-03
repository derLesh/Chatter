package dev.chatter.app.irc

/**
 * Which channels to JOIN next, and when.
 *
 * Twitch lets a normal account join [limit] channels per [windowMs] and silently ignores every
 * JOIN past that — no error, the channel simply stays empty. Sending all of them at once after a
 * connect was fine for a handful of channels and lost the rest of a long list on every reconnect,
 * until one happened to get through. So the joins go out in portions the limit allows, the
 * channels the user most wants to see first.
 *
 * A JOIN can also be lost for reasons nobody reports. Twitch answers a JOIN that worked with a
 * ROOMSTATE, so a channel that has not had one within [answerTimeoutMs] is asked for again, with
 * the wait doubling each time: a suspended channel never answers, and that should cost a JOIN
 * every few minutes rather than one every half minute all night.
 *
 * Not thread-safe; [IrcConnection] calls it under its lock. The clock is passed in so the pacing
 * can be tested without waiting for it.
 */
internal class JoinQueue(
    private val clock: () -> Long,
    /** Lower goes first: the channel on screen, then the ones that notify, then the rest. */
    private val rank: (String) -> Int = { 0 },
    private val limit: Int = 20,
    private val windowMs: Long = 10_000L,
    private val answerTimeoutMs: Long = 30_000L,
) {
    /** When each JOIN in the current window went out, oldest first. */
    private val sent = ArrayDeque<Long>()

    /** Channels still to be sent, in the order they were asked for. */
    private val waiting = LinkedHashSet<String>()

    /** Channels sent and not yet answered: when the next try is due, and how many there were. */
    private val unanswered = HashMap<String, Pending>()

    private class Pending(val dueAt: Long, val tries: Int)

    /** Asks for [channel] to be joined. Nothing happens for one already waiting or sent. */
    fun add(channel: String) {
        if (channel !in unanswered) waiting.add(channel)
    }

    /** Forgets [channel], whatever it was waiting for. */
    fun remove(channel: String) {
        waiting.remove(channel)
        unanswered.remove(channel)
    }

    /** Twitch has answered the JOIN of [channel]; it needs nothing more. */
    fun answered(channel: String) {
        unanswered.remove(channel)
    }

    /**
     * A new connection, which knows none of [channels] yet: all of them wait again. What went out
     * on the old connection still counts against the limit — it is the account's, not the socket's.
     */
    fun restart(channels: Collection<String>) {
        waiting.clear()
        unanswered.clear()
        waiting.addAll(channels)
    }

    /** The channels to send a JOIN for now, best first; empty while the limit is used up. */
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

    /** When [take] could have something again, or null when nothing is left to send or wait for. */
    fun nextDueAt(): Long? {
        val freed = if (sent.size >= limit) sent.first() + windowMs else null
        val waitingNow = if (waiting.isNotEmpty()) (freed ?: clock()) else null
        val retry = unanswered.values.minOfOrNull { it.dueAt }?.let { maxOf(it, freed ?: it) }
        return listOfNotNull(waitingNow, retry).minOrNull()
    }

    private companion object {
        /** Thirty seconds doubled five times: a channel that never answers is asked every 16 minutes at most. */
        const val MAX_DOUBLINGS = 5
    }
}
