package me.lesh.chatter.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import me.lesh.chatter.debug.AppLog
import me.lesh.chatter.debug.LogPrivacy
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Hangs (ANR) and crashes in native code of earlier runs, as Android remembers them
 * ([ApplicationExitInfo]). Chatter's own handler only sees Kotlin errors; these end the process
 * without it. Read once a start, they join the crashes with the log before them. Every end is
 * also written into the log, by `BackgroundHealth`.
 */
class EarlierExits(private val activities: ActivityManager, private val crashes: CrashLog, private val seen: File) {
    fun check(now: Long = System.currentTimeMillis()) {
        val exits = runCatching { activities.getHistoricalProcessExitReasons(null, 0, MAX_EXITS) }.getOrDefault(emptyList())
        // The first time, only the last few days: older ends are of no use any more.
        val after = readSeen() ?: (now - FIRST_LOOK_BACK_MS)
        val fresh = exits.filter { it.timestamp > after }.sortedBy { it.timestamp }
        // Marked before they are handled, so one that fails to be handled is not tried on every start.
        // Never up to now: Android writes a native crash down only after the next start may have
        // begun, with the time it happened.
        writeSeen(maxOf(after, fresh.lastOrNull()?.timestamp ?: after))
        fresh.forEach { exit -> runCatching { handle(exit) } }
    }

    private fun handle(exit: ApplicationExitInfo) {
        val trace = when (exit.reason) {
            ApplicationExitInfo.REASON_ANR -> {
                val threads = exit.traceInputStream?.use { it.readNBytes(MAX_ANR_TRACE_BYTES).decodeToString() }.orEmpty()
                "ANR: ${exit.description?.let(LogPrivacy::clean) ?: "not responding"}\n" + AnrTrace.shorten(threads)
            }
            ApplicationExitInfo.REASON_CRASH_NATIVE -> {
                val tombstone = exit.traceInputStream?.use { it.readNBytes(MAX_TOMBSTONE_BYTES) }?.let(NativeTombstone::describe)
                "Native crash: " + (tombstone ?: exit.description?.let(LogPrivacy::clean) ?: "signal ${exit.status}")
            }
            else -> return
        }
        val before = logBefore(exit.timestamp)
        crashes.writeExit(
            at = exit.timestamp,
            kind = if (exit.reason == ApplicationExitInfo.REASON_ANR) Crash.HANG else Crash.NATIVE,
            trace = trace,
            version = versionIn(before),
            log = CrashLog.logTail(before),
        )
    }

    /** The log of the run that ended, oldest first: its day, and the one before after midnight. */
    private fun logBefore(at: Long): List<AppLog.Entry> {
        val day = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
        return (AppLog.day(day.minusDays(1)) + AppLog.day(day)).filter { it.at <= at }
    }

    private fun readSeen(): Long? = runCatching { seen.readText().trim().toLong() }.getOrNull()

    private fun writeSeen(at: Long) {
        runCatching {
            seen.parentFile?.mkdirs()
            seen.writeText(at.toString())
        }
    }

    companion object {
        /** Android keeps a few per app; more would only be older. */
        private const val MAX_EXITS = 16

        private const val FIRST_LOOK_BACK_MS = 3 * 24 * 60 * 60 * 1000L

        /** Every thread's stack; far more than a report keeps, see [AnrTrace]. */
        private const val MAX_ANR_TRACE_BYTES = 1_000_000

        /** With memory around the fault and the system log, a tombstone runs to a few megabytes. */
        private const val MAX_TOMBSTONE_BYTES = 8_000_000

        /** "0.14.0 (16)" from the run's "Started Chatter …" line, as that run may have been an older version. Internal for tests. */
        internal fun versionIn(log: List<AppLog.Entry>): String? =
            log.lastOrNull { it.tag == "App" && it.private.startsWith(STARTED) }
                ?.private?.removePrefix(STARTED)?.substringBefore(", process")

        private const val STARTED = "Started Chatter "
    }
}

/**
 * The thread dump Android writes for a hang, cut to what a report can hold: the main thread first,
 * as a hang is the main thread stuck, then threads running Chatter's code, then the rest.
 */
internal object AnrTrace {
    /** The same room a crash's trace gets. */
    private const val BUDGET = 12_000

    private const val MAX_HEAD_CHARS = 2_000

    fun shorten(dump: String, budget: Int = BUDGET): String {
        // One block a thread, starting with its quoted name: "main" prio=5 tid=1 Blocked.
        val threads = dump.replace("\r\n", "\n").split(Regex("\n\\s*\n")).map { it.trim('\n') }.filter { it.startsWith('"') }
        // Without stacks (a frozen process writes none) only the opening lines: what follows is
        // Android's own record of recent events, other apps' included.
        if (threads.isEmpty()) return CrashLog.clean(dump.trimStart().substringBefore("\n\n").take(MAX_HEAD_CHARS)).trimEnd()
        val main = threads.filter { it.startsWith("\"main\"") }
        val ours = threads.filter { it !in main && "me.lesh.chatter" in it }
        val ordered = main + ours + (threads - main.toSet() - ours.toSet())
        val kept = StringBuilder()
        var count = 0
        for (thread in ordered) {
            val text = CrashLog.clean(thread)
            val room = budget - kept.length - 2
            if (text.length > room) {
                // The first thread is cut rather than left out; later ones go whole or not at all.
                if (count == 0) {
                    kept.append(text.take(budget))
                    count++
                }
                break
            }
            if (count > 0) kept.append("\n\n")
            kept.append(text)
            count++
        }
        if (count < ordered.size) kept.append("\n\n… ${ordered.size - count} more threads")
        return kept.toString()
    }
}
