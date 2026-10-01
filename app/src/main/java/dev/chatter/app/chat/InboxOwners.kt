package dev.chatter.app.chat

/**
 * Which account a row of the mention or whisper inbox belongs to, and what becomes of rows whose
 * account is gone.
 *
 * A whisper is a private message to one account. With several accounts on one phone, or one
 * logged out and another logged in, the inbox must not hand it to whoever is there now — so every
 * row carries the user id of the account that received it, and leaves with that account.
 */
object InboxOwners {
    /**
     * [list] without the rows of accounts that are not among [accounts] any more.
     *
     * Rows from before inboxes knew their account have no owner. With exactly one account there
     * is only one they can have been for, so they become its; with more, or none, nobody can say
     * whose they were, and they go.
     *
     * Hands [list] itself back when nothing changes, which is how the stores tell a no-op apart.
     */
    fun <T> keepOnly(
        list: List<T>,
        accounts: Collection<String>,
        owner: (T) -> String?,
        adopt: (T, String) -> T,
    ): List<T> {
        val only = accounts.singleOrNull()
        if (list.all { row -> owner(row)?.let { it in accounts } == true }) return list
        return list.mapNotNull { row ->
            val o = owner(row)
            when {
                o == null -> only?.let { adopt(row, it) }
                o in accounts -> row
                else -> null
            }
        }
    }
}
