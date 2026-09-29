package dev.chatter.app.irc

/**
 * One parsed IRC line, e.g.
 * `@badges=moderator/1;color=#FF0000 :nick!nick@nick.tmi.twitch.tv PRIVMSG #channel :hello`
 *
 * The tags stay in the line they came in and are looked up when they are read. A chat message
 * carries twenty of them and the app reads about half; parsing all of them into a map was a key, a
 * value and a map entry apiece for every message in every joined channel, all night in the
 * background, and most of it garbage straight away. Only [tags], for the few lines that want
 * them all, builds the map.
 *
 * The line may be a whole frame from the socket with this line somewhere in it; [parse] is told
 * where, so a frame does not have to be cut into lines first.
 */
class IrcMessage private constructor(
    private val line: String,
    /** Where the tags are in [line], without the "@" and the space after them; empty if there are none. */
    private val tagsStart: Int,
    private val tagsEnd: Int,
    /** Where the prefix is in [line], without the ":"; -1 if there is none. */
    private val prefixStart: Int,
    private val prefixEnd: Int,
    val command: String,
    val params: List<String>,
) {
    /** "#channel" -> "channel" for commands whose first param is a channel. */
    val channel: String?
        get() = params.firstOrNull()?.takeIf { it.startsWith("#") }?.substring(1)

    /** The trailing parameter (the chat text for PRIVMSG). */
    val trailing: String?
        get() = if (params.size >= 2) params.last() else null

    /** The prefix, `nick!user@host` or a server name. */
    val prefix: String?
        get() = if (prefixStart < 0) null else line.substring(prefixStart, prefixEnd)

    /** Login name of the sender, taken from the prefix `nick!user@host`. */
    val nick: String?
        get() {
            if (prefixStart < 0) return null
            val bang = line.indexOf('!', prefixStart).let { if (it == -1 || it > prefixEnd) prefixEnd else it }
            return if (bang > prefixStart) line.substring(prefixStart, bang) else null
        }

    /** Every tag, empty ones included. Built when first asked for; [tag] is the cheap way to one. */
    val tags: Map<String, String>
        get() = parsedTags ?: parseTags(line, tagsStart, tagsEnd).also { parsedTags = it }

    // A field rather than `by lazy`, which would be one more object for every message only to
    // build a map for the few that are ever asked for one.
    @Volatile private var parsedTags: Map<String, String>? = null

    /** The value of the tag [name], or null if it is missing or empty. */
    fun tag(name: String): String? {
        var pos = tagsStart
        while (pos < tagsEnd) {
            var sep = line.indexOf(';', pos)
            if (sep == -1 || sep > tagsEnd) sep = tagsEnd
            val keyEnd = pos + name.length
            if (keyEnd <= sep && line.regionMatches(pos, name, 0, name.length) && (keyEnd == sep || line[keyEnd] == '=')) {
                return if (keyEnd + 1 >= sep) null else unescapeTagValue(line, keyEnd + 1, sep)
            }
            pos = sep + 1
        }
        return null
    }

    override fun toString(): String = "IrcMessage($command $params)"

    companion object {
        /**
         * Parses the IRC line between [start] and [end] of [text] (without CR/LF). Returns null for
         * empty or malformed lines.
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
