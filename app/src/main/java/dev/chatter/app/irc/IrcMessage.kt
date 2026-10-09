package dev.chatter.app.irc

/**
 * One parsed IRC line, e.g. `@badges=moderator/1;color=#FF0000 :nick!nick@nick.tmi.twitch.tv
 * PRIVMSG #channel :hello`
 *
 * Tags stay in the original string and are looked up on demand: a message carries about twenty and
 * the app reads half, and building a map for each message in every channel all night was mostly
 * garbage. [tags] builds the map for the few callers that want all of them.
 *
 * [line] may be a whole socket frame; [parse] gets the bounds of this line in it.
 */
class IrcMessage private constructor(
    private val line: String,
    /** Tag section in [line] without the "@" and trailing space; empty if there are none. */
    private val tagsStart: Int,
    private val tagsEnd: Int,
    /** Prefix in [line] without the ":"; -1 if there is none. */
    private val prefixStart: Int,
    private val prefixEnd: Int,
    val command: String,
    val params: List<String>,
) {
    /**
     * "#channel" -> "channel" for commands whose first parameter is a channel. Cut once: the chat
     * and the builder both ask for every message.
     */
    val channel: String? = params.firstOrNull()?.takeIf { it.startsWith("#") }?.substring(1)

    /** The trailing parameter (the chat text for PRIVMSG). */
    val trailing: String?
        get() = if (params.size >= 2) params.last() else null

    /** The prefix, `nick!user@host` or a server name. */
    val prefix: String?
        get() = if (prefixStart < 0) null else line.substring(prefixStart, prefixEnd)

    /** Sender login, from the prefix `nick!user@host`. */
    val nick: String?
        get() {
            if (prefixStart < 0) return null
            val bang = line.indexOf('!', prefixStart).let { if (it == -1 || it > prefixEnd) prefixEnd else it }
            return if (bang > prefixStart) line.substring(prefixStart, bang) else null
        }

    /** Every tag, empty ones included, built on first use. [tag] is cheaper for a single one. */
    val tags: Map<String, String>
        get() = parsedTags ?: parseTags(line, tagsStart, tagsEnd).also { parsedTags = it }

    // A field instead of `by lazy`, which would cost an extra object per message.
    @Volatile private var parsedTags: Map<String, String>? = null

    /** The value of the tag [name], or null if it is missing or empty. */
    fun tag(name: String): String? {
        val start = valueStart(name)
        if (start < 0) return null
        val end = valueEnd(start)
        return if (start >= end) null else unescapeTagValue(line, start, end)
    }

    /** Whether the tag [name] is exactly [value]; compared in place, without cutting it out. */
    fun tagIs(name: String, value: String): Boolean {
        val start = valueStart(name)
        return start >= 0 && valueEnd(start) - start == value.length && line.regionMatches(start, value, 0, value.length)
    }

    /** The tag [name] as a number, read in place; null if it is missing or not a number. */
    fun tagLong(name: String): Long? {
        val start = valueStart(name)
        if (start < 0) return null
        val end = valueEnd(start)
        if (start >= end || end - start > MAX_DIGITS) return null
        var value = 0L
        for (i in start until end) {
            val digit = line[i] - '0'
            if (digit !in 0..9) return null
            value = value * 10 + digit
        }
        return value
    }

    /** Where the value of the tag [name] starts in [line], or -1 if there is no such tag. */
    private fun valueStart(name: String): Int {
        var pos = tagsStart
        while (pos < tagsEnd) {
            val sep = valueEnd(pos)
            val keyEnd = pos + name.length
            if (keyEnd <= sep && line.regionMatches(pos, name, 0, name.length) && (keyEnd == sep || line[keyEnd] == '=')) {
                return if (keyEnd == sep) sep else keyEnd + 1
            }
            pos = sep + 1
        }
        return -1
    }

    /** The end of the tag that runs through [pos]. */
    private fun valueEnd(pos: Int): Int = line.indexOf(';', pos).let { if (it == -1 || it > tagsEnd) tagsEnd else it }

    override fun toString(): String = "IrcMessage($command $params)"

    companion object {
        /** More digits than a Long holds; Twitch's numbers are timestamps and counts. */
        private const val MAX_DIGITS = 18

        /**
         * Parses the line between [start] and [end] of [text] (without CR/LF). Null for empty or
         * malformed lines.
         */
        fun parse(text: String, start: Int = 0, end: Int = text.length): IrcMessage? {
            if (end <= start) return null
            var pos = start

            var tagsStart = 0
            var tagsEnd = 0
            if (text[pos] == '@') {
                val space = text.indexOf(' ', pos)
                if (space == -1 || space >= end) return null
                tagsStart = pos + 1
                tagsEnd = space
                pos = space + 1
            }
            while (pos < end && text[pos] == ' ') pos++

            var prefixStart = -1
            var prefixEnd = -1
            if (pos < end && text[pos] == ':') {
                val space = text.indexOf(' ', pos)
                if (space == -1 || space >= end) return null
                prefixStart = pos + 1
                prefixEnd = space
                pos = space + 1
            }
            while (pos < end && text[pos] == ' ') pos++

            val cmdEnd = text.indexOf(' ', pos).let { if (it == -1 || it > end) end else it }
            if (cmdEnd <= pos) return null
            val command = text.substring(pos, cmdEnd)
            pos = cmdEnd

            val params = ArrayList<String>(4)
            while (pos < end) {
                while (pos < end && text[pos] == ' ') pos++
                if (pos >= end) break
                if (text[pos] == ':') {
                    params.add(text.substring(pos + 1, end))
                    break
                }
                val paramEnd = text.indexOf(' ', pos).let { if (it == -1 || it > end) end else it }
                params.add(text.substring(pos, paramEnd))
                pos = paramEnd
            }
            return IrcMessage(text, tagsStart, tagsEnd, prefixStart, prefixEnd, command, params)
        }

        private fun parseTags(line: String, start: Int, end: Int): Map<String, String> {
            if (start >= end) return emptyMap()
            val result = HashMap<String, String>(32)
            var pos = start
            while (pos < end) {
                var sep = line.indexOf(';', pos)
                if (sep == -1 || sep > end) sep = end
                val eq = line.indexOf('=', pos)
                if (eq in pos until sep) {
                    result[line.substring(pos, eq)] = unescapeTagValue(line, eq + 1, sep)
                } else {
                    result[line.substring(pos, sep)] = ""
                }
                pos = sep + 1
            }
            return result
        }

        /** IRCv3 tag value unescaping: `\s` space, `\:` semicolon, `\\` backslash, `\r`, `\n`. */
        private fun unescapeTagValue(line: String, start: Int, end: Int): String {
            if (line.indexOf('\\', start).let { it == -1 || it >= end }) return line.substring(start, end)
            val sb = StringBuilder(end - start)
            var i = start
            while (i < end) {
                val c = line[i]
                if (c == '\\' && i + 1 < end) {
                    when (line[i + 1]) {
                        's' -> sb.append(' ')
                        ':' -> sb.append(';')
                        '\\' -> sb.append('\\')
                        'r' -> sb.append('\r')
                        'n' -> sb.append('\n')
                        else -> sb.append(line[i + 1])
                    }
                    i += 2
                } else {
                    if (c != '\\') sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        fun escapeTagValue(value: String): String = buildString(value.length) {
            for (c in value) when (c) {
                ' ' -> append("\\s")
                ';' -> append("\\:")
                '\\' -> append("\\\\")
                '\r' -> append("\\r")
                '\n' -> append("\\n")
                else -> append(c)
            }
        }
    }
}
