package me.lesh.chatter.debug

import me.lesh.chatter.crash.CrashLog

/**
 * Takes out of a log line what could tell who uses the app: login secrets, links with their path
 * and query (a channel name or user id is often in there), error pages, email and IP addresses.
 */
internal object LogPrivacy {
    private const val HIDDEN = "[hidden]"

    // Path and query go, the host stays: which service failed is the useful part. Any case, since
    // a link typed in chat can start with "Https://".
    private val URL = Regex("""(?i)\b((?:https?|wss?)://[A-Za-z0-9.-]+(?::\d+)?)[/?#][^\s"'<>)\]]*""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    // An error page can quote what was asked for, a name included; see HttpException.
    private val HTTP_BODY = Regex("""(?s)\b(HTTP \d{3}): .*""")
    private val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    // What may be an IPv6 address: groups of hex digits and colons, maybe with "::" for the zeros
    // left out, an IPv4 address at the end and a zone like %wlan0. isIpv6 decides.
    private val IPV6_CANDIDATE = Regex(
        """(?<![\w:])(?:[0-9A-Fa-f]{0,4}:){2,7}(?:(?:\d{1,3}\.){3}\d{1,3}|[0-9A-Fa-f]{1,4})?(?:%[\w.-]+)?(?![\w:])""",
    )
    private val HEX_GROUP = Regex("[0-9A-Fa-f]{1,4}")

    fun clean(text: String): String {
        var result = CrashLog.hideSecrets(text)
        result = HTTP_BODY.replace(result) { it.groupValues[1] }
        result = URL.replace(result) { "${it.groupValues[1]}/…" }
        result = EMAIL.replace(result, HIDDEN)
        result = IPV6_CANDIDATE.replace(result) { if (isIpv6(it.value)) HIDDEN else it.value }
        result = IPV4.replace(result, HIDDEN)
        return result
    }

    /**
     * Whether [text] reads as an IPv6 address: with "::" and at least one group, or written out with
     * at least four groups. A time like 12:30:05 has three and stays; so does a lone "::".
     */
    private fun isIpv6(text: String): Boolean {
        val address = text.substringBefore('%').let { a ->
            // An IPv4 address at the end stands for the last two groups.
            if ('.' in a) a.substringBeforeLast(':') + ":0:0" else a
        }
        val halves = address.split("::")
        if (halves.size > 2) return false
        val groups = halves.flatMap { half -> if (half.isEmpty()) emptyList() else half.split(':') }
        if (groups.any { !HEX_GROUP.matches(it) }) return false
        return if (halves.size == 2) groups.size in 1..7 else groups.size in 4..8
    }
}
