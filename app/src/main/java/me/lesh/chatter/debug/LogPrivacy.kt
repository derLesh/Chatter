package me.lesh.chatter.debug

import me.lesh.chatter.crash.CrashLog

/**
 * Takes out of a log line what could tell who uses the app: login secrets, links with their path
 * and query (a channel name or user id is often in there), error pages, email and IP addresses.
 */
internal object LogPrivacy {
    private const val HIDDEN = "[hidden]"

    // Path and query go, the host stays: which service failed is the useful part.
    private val URL = Regex("""\b((?:https?|wss?)://[A-Za-z0-9.-]+(?::\d+)?)[/?#][^\s"'<>)\]]*""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    // An error page can quote what was asked for, a name included; see HttpException.
    private val HTTP_BODY = Regex("""(?s)\b(HTTP \d{3}): .*""")
    private val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    // Three or more groups, so a time like 12:30 or a ratio stays.
    private val IPV6 = Regex("""\b(?:[0-9A-Fa-f]{1,4}:){3,7}[0-9A-Fa-f]{0,4}\b|::(?:[0-9A-Fa-f]{1,4}:){1,6}[0-9A-Fa-f]{1,4}\b""")

    fun clean(text: String): String {
        var result = CrashLog.hideSecrets(text)
        result = HTTP_BODY.replace(result) { it.groupValues[1] }
        result = URL.replace(result) { "${it.groupValues[1]}/…" }
        result = EMAIL.replace(result, HIDDEN)
        result = IPV6.replace(result, HIDDEN)
        result = IPV4.replace(result, HIDDEN)
        return result
    }
}
