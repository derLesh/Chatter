package dev.chatter.app.ai

import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.MessageKind

/**
 * Turns the messages that arrived while the user was away into a prompt for a short summary.
 * Gemini Nano reads about 4000 tokens, so a long absence is cut down to [BUDGET] characters.
 */
object CatchUp {
    /** Below this, reading the messages is quicker than waiting for a summary. */
    const val MIN_MESSAGES = 30

    /** About half the model's window; chat names and emotes cost more tokens than plain words. */
    const val BUDGET = 6_000

    /** A single message is cut here; a wall of copypasta says no more than its start. */
    const val MAX_LINE = 200

    /** The chat as plain lines, oldest first; [withChannel] marks each with its channel. */
    fun lines(missed: List<ChatItem>, withChannel: Boolean = false): List<String> = missed.mapNotNull { line(it, withChannel) }

    /**
     * [lines] in at most [budget] characters. Too many are thinned out evenly rather than cut at
     * one end, so the summary still covers the whole time away.
     */
    fun fit(lines: List<String>, budget: Int = BUDGET): String {
        val total = lines.sumOf { it.length + 1 }
        if (total <= budget) return lines.joinToString("\n")
        val keep = (budget.toLong() * lines.size / total).toInt().coerceIn(1, lines.size)
        val picked = ArrayDeque((0 until keep).map { lines[(it.toLong() * lines.size / keep).toInt()] })
        var length = picked.sumOf { it.length + 1 }
        while (length > budget && picked.size > 1) length -= picked.removeFirst().length + 1
        return picked.joinToString("\n")
    }

    /**
     * What the model is asked, followed by the chat. It names no example topics or emotes: a model
     * short of chat repeats them as the summary. Written in English, which it follows best;
     * [language] is the English name of the language the answer should be in, as
     * `Locale.getDisplayLanguage(Locale.ENGLISH)` gives it. [ownLogin] lets it point out what was
     * said to the user.
     */
    fun prompt(channel: String, language: String, ownLogin: String?): String = buildString {
        append("Below is a part of the Twitch chat of the channel \"").append(channel).append("\" that the user missed.\n")
        append("Sum up what was talked about in at most three short bullet points, in ").append(language).append(".\n")
        append("Lines in square brackets are from Twitch, such as a raid or a sub; mention them only if there are any. ")
        append("Single words in capitals are usually emotes and mean a reaction.\n")
        if (!ownLogin.isNullOrBlank()) {
            append("The user is \"").append(ownLogin).append("\"; if somebody asked them something, say so first.\n")
        }
        append("Only state what the chat below says, and nothing that is not in it. Do not add an introduction.\n\n")
        append("Chat:\n")
    }

    /** A refused stretch of chat is narrowed down to this many messages. */
    const val NARROW = 2

    /** Keeps the wait bounded when much of a chat is refused. */
    const val MAX_TRIES = 24

    /**
     * Which of [count] messages a safety check refuses, given [passes] for the messages from `from`
     * up to `to`. Found by halving: a half that passes is kept whole, one that does not is halved
     * again down to [NARROW] messages. After [MAX_TRIES] whatever is still in doubt counts as
     * refused.
     */
    suspend fun refused(count: Int, passes: suspend (from: Int, to: Int) -> Boolean): Set<Int> {
        val refused = HashSet<Int>()
        var tries = 0
        suspend fun narrow(from: Int, to: Int) {
            if (to - from <= NARROW) {
                refused.addAll(from until to)
                return
            }
            val middle = (from + to) / 2
            for ((start, end) in listOf(from to middle, middle to to)) {
                if (tries >= MAX_TRIES) {
                    refused.addAll(start until end)
                    continue
                }
                tries++
                if (!passes(start, end)) narrow(start, end)
            }
        }
        narrow(0, count)
        return refused
    }

    /** The model's answer as plain text: it writes Markdown, which the sheet does not draw. */
    fun tidy(answer: String): String = answer
        .replace("**", "")
        .lines()
        .joinToString("\n") { it.replaceFirst(BULLET, "• ") }
        .trim()

    private val BULLET = Regex("""^\s*[*-]\s+""")

    /** One message as a plain line, or null for one that says nothing, like a deleted message. */
    fun line(item: ChatItem, withChannel: Boolean = false): String? {
        if (item.deleted) return null
        val name = item.displayName ?: item.login
        val text = when (item.kind) {
            MessageKind.Chat -> name?.let { "$it: ${item.text}" }
            MessageKind.Action -> name?.let { "* $it ${item.text}" }
            // A sub, raid or announcement: Twitch's own line, then what the chatter wrote with it.
            MessageKind.UserNotice -> {
                val said = item.text.takeIf { it.isNotBlank() }?.let { " ${name ?: "?"}: $it" }.orEmpty()
                item.systemText?.let { "[$it]$said" } ?: said.trim().takeIf { it.isNotEmpty() }
            }
            MessageKind.Notice -> (item.systemText ?: item.text).takeIf { it.isNotBlank() }?.let { "[$it]" }
        } ?: return null
        val line = if (withChannel) "#${item.channel} $text" else text
        return if (line.length > MAX_LINE) line.take(MAX_LINE - 1) + "…" else line
    }
}
