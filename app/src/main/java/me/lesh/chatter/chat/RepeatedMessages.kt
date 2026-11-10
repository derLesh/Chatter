package me.lesh.chatter.chat

/**
 * Lets the same line go out again right away, the way Chatterino does it. Twitch refuses a message
 * identical to the user's previous one within [WINDOW_MS]; one more space, which chat does not
 * show, makes it a different one.
 */
object RepeatedMessages {
    const val WINDOW_MS = 30_000L

    /** For a line without a space to double: U+034F, which draws nothing. */
    const val SUFFIX = " ͏"

    /**
     * Whether [text] would be refused as a repeat of [previous], the line last sent [sinceMs] ago.
     * [previous] is the line as sent, so a third copy matches the plain first one again and goes
     * out unchanged: the lines alternate instead of growing.
     */
    fun isRepeat(text: String, previous: String?, sinceMs: Long): Boolean =
        previous == text && sinceMs < WINDOW_MS

    /** [text] with its first space doubled, or [SUFFIX] added when it has none. */
    fun vary(text: String): String {
        // Twitch reads "/me" or ".me" up to the first space as the command; that space is not in the message.
        val first = text.indexOf(' ')
        val command = text.startsWith('/') || text.startsWith('.')
        val space = if (command && first >= 0) text.indexOf(' ', first + 1) else if (command) -1 else first
        return if (space < 0) text + SUFFIX else text.substring(0, space) + " " + text.substring(space)
    }
}
