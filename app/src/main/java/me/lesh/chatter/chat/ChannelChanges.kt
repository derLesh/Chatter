package me.lesh.chatter.chat

/** What changed about a stream: the new title and category, null where they stayed. */
data class StreamChange(val title: String?, val category: String?)

/**
 * Tells which part of a `channel.update` is new. Twitch sends one for any change, also of the
 * language or the content labels, which the chat leaves out. Touched on the chat worker only.
 */
class ChannelChanges(
    /** Title and category as the live check last saw them; empty while unknown. */
    private val lastSeen: (channel: String) -> Pair<String, String>,
) {
    private val last = HashMap<String, Pair<String, String>>()

    /** Null if neither title nor category changed. */
    fun changed(channel: String, title: String, category: String): StreamChange? {
        val (oldTitle, oldCategory) = last[channel] ?: lastSeen(channel)
        last[channel] = title to category
        val change = StreamChange(
            title = title.takeIf { it.isNotBlank() && it != oldTitle },
            category = category.takeIf { it.isNotBlank() && it != oldCategory },
        )
        return change.takeIf { it.title != null || it.category != null }
    }

    fun forget(channel: String) = last.remove(channel)

    fun clear() = last.clear()
}
