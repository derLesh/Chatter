package dev.chatter.app.chat

/**
 * Which account a mention or whisper row belongs to, and what happens to rows whose account is
 * gone. A whisper is private to one account, so every row carries the receiving account's user id
 * and leaves with it.
 */
object InboxOwners {
    /**
     * [list] without rows of accounts no longer in [accounts].
     *
     * Rows from before owners were stored have none. With exactly one account they become its rows;
     * otherwise nobody can tell and they are dropped.
     *
     * Returns [list] itself when nothing changes, so the stores can skip the write.
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
