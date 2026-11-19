package me.lesh.chatter.crash

import me.lesh.chatter.debug.LogReport

/**
 * A crash put into the fields of a new GitHub issue. GitHub refuses links much longer than 8 000
 * characters, so a long trace keeps each error's first lines and drops the rest, and the log keeps
 * its newest lines. [whole] is false when something had to go; the whole crash then goes into the
 * clipboard for pasting. [version] and [android] are the crash's own, null if it does not say;
 * an update since may have changed them.
 */
class CrashIssue(
    val title: String,
    val crash: String,
    val log: String,
    val whole: Boolean,
    val version: String?,
    val android: String?,
)

object CrashReport {
    /** Leaves room in the link for the template, the title, the version and the phone. */
    private const val ISSUE_CHARS = 6_500

    /** The trace matters more, but the log should keep at least this much room when the trace is long. */
    private const val LOG_SHARE = 0.35

    /** Frames kept per error, tried in turn until the trace fits; null keeps them all. */
    private val FRAME_LIMITS = listOf(null, 20, 12, 8, 5, 3, 1, 0)

    /** An error message can quote a whole server answer. */
    private const val MAX_MESSAGE_CHARS = 400

    fun forIssue(crash: Crash, maxEncodedChars: Int = ISSUE_CHARS): CrashIssue {
        val head = crash.text.substringBefore("\n\n").trim() + "\n\n"
        val logLines = crash.log.lines().filter { it.isNotBlank() }
        val logCost = encodedLength(logLines.joinToString("\n"))
        val traceBudget = maxEncodedChars - minOf(logCost, (maxEncodedChars * LOG_SHARE).toInt())
        val (trace, traceWhole) = shortTrace(crash.trace, traceBudget - encodedLength(head), ::encodedLength)
        val crashText = head + trace
        val (log, logWhole) = newestLines(logLines, maxEncodedChars - encodedLength(crashText))
        return CrashIssue(
            // A hang or a native crash names its kind; Chatter's own crashes name the error.
            title = crash.header["Kind"] ?: "Crash: ${crash.errorKind}",
            crash = crashText,
            log = log,
            whole = traceWhole && logWhole,
            version = crash.header["Chatter"],
            android = crash.header["Android"],
        )
    }

    /**
     * [trace] cut to fit [budget] as [measure] counts it: every error and cause stays, with as
     * many of its first frames as fit. The second value is false when anything was left out.
     */
    fun shortTrace(trace: String, budget: Int, measure: (String) -> Int): Pair<String, Boolean> {
        if (measure(trace) <= budget) return trace to true
        val blocks = blocks(trace)
        for (limit in FRAME_LIMITS) {
            val text = blocks.joinToString("\n") { it.text(limit) }
            if (measure(text) <= budget) return text to false
        }
        // Even the errors alone are too long: keep their start.
        val errors = blocks.joinToString("\n") { it.text(0) }
        var end = errors.length
        while (end > 0 && measure(errors.substring(0, end)) > budget) end = end * 3 / 4
        return errors.substring(0, end) to false
    }

    private fun newestLines(lines: List<String>, budget: Int): Pair<String, Boolean> {
        val whole = lines.joinToString("\n")
        if (encodedLength(whole) <= budget) return whole to true
        val kept = ArrayDeque<String>()
        var left = budget - encodedLength(CUT_LOG_NOTE + "\n")
        if (left <= 0) return "" to false
        for (line in lines.asReversed()) {
            val cost = encodedLength(line + "\n")
            if (cost > left) break
            left -= cost
            kept.addFirst(line)
        }
        return (CUT_LOG_NOTE + "\n" + kept.joinToString("\n")).trimEnd() to false
    }

    /** One error of a trace: its line ("Caused by: …") and the frames below it. */
    private class Block(val head: String, val frames: List<String>) {
        fun text(limit: Int?): String {
            if (limit == null) return (listOf(head) + frames).joinToString("\n")
            // Java's own "... 12 more" would be counted as a frame.
            val calls = frames.filter { it.trimStart().startsWith("at ") }
            val shown = calls.take(limit)
            val left = calls.size - shown.size
            val headLine = if (head.length <= MAX_MESSAGE_CHARS) head else head.take(MAX_MESSAGE_CHARS) + " …"
            return (listOf(headLine) + shown + listOfNotNull("\t… $left more".takeIf { left > 0 })).joinToString("\n")
        }
    }

    /** A frame is "\tat …" or "\t... 12 more"; every other line opens an error. */
    private fun blocks(trace: String): List<Block> {
        val blocks = mutableListOf<Pair<String, MutableList<String>>>()
        for (line in trace.lines()) {
            val bare = line.trimStart()
            val isFrame = line != bare && (bare.startsWith("at ") || bare.startsWith("..."))
            if (isFrame && blocks.isNotEmpty()) blocks.last().second += line
            else blocks += line to mutableListOf()
        }
        return blocks.map { (head, frames) -> Block(head, frames) }
    }

    private const val CUT_LOG_NOTE = "(Only the newest lines fit here. The whole crash is in the clipboard.)"

    private fun encodedLength(text: String) = LogReport.uriEncodedLength(text)
}
