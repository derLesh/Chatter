package dev.chatter.app.irc

/**
 * One parsed IRC line, e.g.
 * `@badges=moderator/1;color=#FF0000 :nick!nick@nick.tmi.twitch.tv PRIVMSG #channel :hello`
 */
data class IrcMessage(
    val tags: Map<String, String>,
    val prefix: String?,
    val command: String,
    val params: List<String>,
) {
    /** "#channel" -> "channel" for commands whose first param is a channel. */
    val channel: String?
        get() = params.firstOrNull()?.takeIf { it.startsWith("#") }?.substring(1)

    /** The trailing parameter (the chat text for PRIVMSG). */
    val trailing: String?
        get() = if (params.size >= 2) params.last() else null

    /** Login name of the sender, taken from the prefix `nick!user@host`. */
    val nick: String?
        get() = prefix?.substringBefore('!')?.takeIf { it.isNotEmpty() }

    fun tag(name: String): String? = tags[name]?.takeIf { it.isNotEmpty() }

    companion object {
        /** Parses a single IRC line (without trailing CR/LF). Returns null for empty or malformed lines. */
        fun parse(line: String): IrcMessage? {
            if (line.isEmpty()) return null
            var pos = 0
            val len = line.length

            var tags: Map<String, String> = emptyMap()
            if (line[0] == '@') {
                val end = line.indexOf(' ')
                if (end == -1) return null
                tags = parseTags(line, 1, end)
                pos = end + 1
            }
            while (pos < len && line[pos] == ' ') pos++

            var prefix: String? = null
            if (pos < len && line[pos] == ':') {
                val end = line.indexOf(' ', pos)
                if (end == -1) return null
                prefix = line.substring(pos + 1, end)
                pos = end + 1
            }
            while (pos < len && line[pos] == ' ') pos++

            val cmdEnd = line.indexOf(' ', pos).let { if (it == -1) len else it }
            if (cmdEnd <= pos) return null
            val command = line.substring(pos, cmdEnd)
            pos = cmdEnd

            val params = ArrayList<String>(4)
            while (pos < len) {
                while (pos < len && line[pos] == ' ') pos++
                if (pos >= len) break
                if (line[pos] == ':') {
                    params.add(line.substring(pos + 1))
                    break
                }
                val end = line.indexOf(' ', pos).let { if (it == -1) len else it }
                params.add(line.substring(pos, end))
                pos = end
            }
            return IrcMessage(tags, prefix, command, params)
        }

        private fun parseTags(line: String, start: Int, end: Int): Map<String, String> {
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
