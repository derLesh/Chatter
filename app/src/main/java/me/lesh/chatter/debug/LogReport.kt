package me.lesh.chatter.debug

import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The log as text: the device on top, then one line per entry, oldest first. [full] picks the log
 * with names and links instead of the private one; see [AppLog].
 */
object LogReport {
    fun text(device: List<Pair<String, String>>, entries: List<AppLog.Entry>, full: Boolean): String = buildString {
        append(deviceText(device))
        appendLine(if (full) FULL_NOTE else PRIVATE_NOTE)
        appendLine()
        entries.forEach { appendLine(line(it, full)) }
    }.trimEnd()

    fun deviceText(device: List<Pair<String, String>>): String = buildString {
        device.forEach { (label, value) -> appendLine("$label: $value") }
    }

    /** "2026-11-12 14:03:07.215Z W Http: GET api.twitch.tv/helix/users → 401 in 210 ms", in UTC like the crash log. */
    fun line(entry: AppLog.Entry, full: Boolean): String =
        "${time(entry.at)} ${entry.level.letter} ${entry.tag}: ${entry.message(full)}"

    /**
     * What fits into the log field of a new GitHub issue: the device and as many of the newest
     * lines as stay under [maxEncodedChars] once put into the link. GitHub refuses links much longer
     * than 8 000 characters. [IssueLog.whole] is false when older lines had to be left out.
     */
    fun forIssue(
        device: List<Pair<String, String>>,
        entries: List<AppLog.Entry>,
        full: Boolean,
        maxEncodedChars: Int = ISSUE_LOG_CHARS,
    ): IssueLog {
        val head = deviceText(device) + (if (full) FULL_NOTE else PRIVATE_NOTE) + "\n"
        val lines = entries.map { line(it, full) }
        val full = head + "\n" + lines.joinToString("\n")
        if (encodedLength(full) <= maxEncodedChars) return IssueLog(full, whole = true)
        val note = "(Only the newest lines fit here. The whole log is in the clipboard.)\n"
        var budget = maxEncodedChars - encodedLength(head + "\n" + note)
        val kept = ArrayDeque<String>()
        for (line in lines.asReversed()) {
            val cost = encodedLength(line + "\n")
            if (cost > budget) break
            budget -= cost
            kept.addFirst(line)
        }
        return IssueLog(head + "\n" + note + kept.joinToString("\n"), whole = false)
    }

    class IssueLog(val text: String, val whole: Boolean)

    private const val PRIVATE_NOTE = "Log: private (no names, links or login)"
    private const val FULL_NOTE = "Log: full (with names and links, no login)"

    /** Leaves room in the link for the template, the version and the phone. */
    private const val ISSUE_LOG_CHARS = 6_500

    private fun encodedLength(text: String) = URLEncoder.encode(text, Charsets.UTF_8).length

    private fun time(at: Long): String = format.get()!!.format(Date(at))

    // SimpleDateFormat is not thread-safe.
    private val format = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }
}
