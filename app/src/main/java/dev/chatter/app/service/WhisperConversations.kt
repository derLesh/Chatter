package dev.chatter.app.service

/**
 * The whisper conversations in the notifications, one per sender, all of them the active
 * account's.
 *
 * A line names the account it belongs to and is only taken while that account is active, so a
 * whisper held up by a picture download or a reply still being sent cannot land in another
 * account's notifications, or put that account behind its reply action. Not thread-safe:
 * [ChatNotifier] calls it under the lock it also posts and cancels under.
 */
internal class WhisperConversations(private val activeAccount: () -> String?) {
    class Line(val text: String, val timestamp: Long, val own: Boolean)

    /** The other person, kept so an answer can go back to them as [owner]. */
    class Thread(val owner: String, var name: String, var userId: String?) {
        val lines = ArrayDeque<Line>()
    }

    private val threads = HashMap<String, Thread>()

    /**
     * Adds [line] to [owner]'s conversation with [login] and returns it to be shown, or null if
     * [owner] is not the active account.
     */
    fun add(owner: String?, login: String, name: String?, userId: String?, line: Line): Thread? {
        if (owner == null || owner != activeAccount()) return null
        val key = login.lowercase()
        val thread = threads[key]?.takeIf { it.owner == owner }
            ?: Thread(owner, name ?: login, userId).also { threads[key] = it }
        name?.let { thread.name = it }
        userId?.let { thread.userId = it }
        thread.lines.addLast(line)
        while (thread.lines.size > MAX_LINES) thread.lines.removeFirst()
        return thread
    }

    /** Forgets every conversation and returns the lowercase logins whose notifications to remove. */
    fun clear(): Set<String> = threads.keys.toSet().also { threads.clear() }

    private companion object {
        const val MAX_LINES = 6
    }
}
