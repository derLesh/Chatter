package me.lesh.chatter.chat

/** The user's own last messages in a channel, as they would be typed to send them again. */
object RecentlySent {
    /**
     * [messages] are the user's own, oldest first; the result is newest first and without
     * repeats, since the same line sent three times would fill the list with one choice.
     */
    fun texts(messages: List<ChatItem>, limit: Int): List<String> =
        messages.asReversed().asSequence()
            .filter { it.kind == MessageKind.Chat || it.kind == MessageKind.Action }
            .map(::typed)
            .filter { it.isNotBlank() }
            .distinct()
            .take(limit)
            .toList()

    /** Twitch passes the invisible tag that lets a line repeat; it would only stack up when sent again. */
    private fun typed(item: ChatItem): String {
        val text = item.text.replace(TAG_CHARACTER, "").trim()
        return if (item.kind == MessageKind.Action) "/me $text" else text
    }

    /** U+E0000, which Chatter and other clients append to send the same line twice in a row. */
    private const val TAG_CHARACTER = "󠀀"
}
