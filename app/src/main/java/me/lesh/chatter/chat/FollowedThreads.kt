package me.lesh.chatter.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Conversations the user follows, so a reply in one counts as a mention even when it does not
 * answer the user. Twitch threads are short: one without a new reply for [QUIET_MS] is let go.
 * Kept in memory only; a thread rarely outlives the app.
 */
class FollowedThreads(private val now: () -> Long = System::currentTimeMillis) {
    private val _followed = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Thread id to the time of its newest reply, in ms. May still hold one gone quiet. */
    val followed: StateFlow<Map<String, Long>> = _followed

    /** Threads the user unfollowed; writing in them again does not follow them. */
    private val unfollowed = ConcurrentHashMap.newKeySet<String>()

    fun isFollowing(threadId: String): Boolean = _followed.value[threadId]?.let { now() - it < QUIET_MS } == true

    /** The user wrote in [threadId], or somebody answered the user there. */
    fun wroteIn(threadId: String) {
        if (threadId !in unfollowed) keep(threadId)
    }

    fun follow(threadId: String) {
        unfollowed.remove(threadId)
        keep(threadId)
    }

    fun unfollow(threadId: String) {
        unfollowed.add(threadId)
        _followed.update { it - threadId }
    }

    /** A reply in [threadId] arrived. True if the thread is followed; the reply keeps it so. */
    fun replied(threadId: String): Boolean {
        if (!isFollowing(threadId)) return false
        keep(threadId)
        return true
    }

    fun clear() {
        _followed.value = emptyMap()
        unfollowed.clear()
    }

    private fun keep(threadId: String) {
        val at = now()
        _followed.update { threads -> threads.filterValues { at - it < QUIET_MS } + (threadId to at) }
    }

    companion object {
        const val QUIET_MS = 3 * 60 * 60 * 1000L
    }
}
