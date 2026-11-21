package me.lesh.chatter.crash

import android.os.Build
import me.lesh.chatter.debug.AppLog
import me.lesh.chatter.debug.LogPrivacy
import me.lesh.chatter.debug.LogReport
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** App and device details for a bug report. */
data class DeviceInfo(val appVersion: String, val versionCode: Int, val android: String, val sdk: Int, val device: String) {
    companion object {
        fun current(appVersion: String, versionCode: Int) = DeviceInfo(
            appVersion = appVersion,
            versionCode = versionCode,
            android = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            // The model alone is often a code name ("GKWS6").
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }
}

/**
 * One recorded crash: when, and the text for the bug report. The text has three parts, split by
 * blank lines: the [header] ("Chatter: 0.9.0 (9)"), the [trace], and the [log] before it, which
 * crashes recorded by older versions lack.
 */
class Crash(val at: Long, val text: String) {
    val header: Map<String, String> by lazy {
        text.substringBefore("\n\n").lines().mapNotNull { line ->
            val colon = line.indexOf(": ")
            if (colon < 0) null else line.substring(0, colon) to line.substring(colon + 2)
        }.toMap()
    }

    val trace: String get() = text.substringAfter("\n\n", "").substringBefore(LOG_HEADING).trim()

    /** The private log's last lines, oldest first; empty for a crash written without them. */
    val log: String get() = text.substringAfter(LOG_HEADING, "").trim()

    /** The trace's first line: "java.lang.IllegalStateException: broken". */
    val error: String get() = trace.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    /** "IllegalStateException", for a list or an issue title; "ANR" for a hang. */
    val errorKind: String get() = error.substringBefore(':').substringAfterLast('.').ifBlank { "Crash" }

    /** Chatter stopped responding rather than closed on an error; see [EarlierExits]. */
    val isHang: Boolean get() = header["Kind"] == HANG

    companion object {
        internal const val LOG_HEADING = "\n\nLog before the crash, private:\n"

        /** Kinds of crash Android reports instead of Chatter's own handler, in the "Kind" header line. */
        const val HANG = "Not responding (ANR)"
        const val NATIVE = "Native crash"
    }
}

/**
 * The last few crashes, stored in the app's own files. Without this the only trace is logcat, which
 * users cannot reach. Each keeps the end of the private log too, so it still says what happened
 * after the log itself has moved on. Nothing is sent anywhere; the user posts it, as the privacy
 * policy says.
 */
class CrashLog(private val dir: File, private val device: DeviceInfo) {
    /**
     * Records every uncaught exception, then passes it to the previous handler (Android's), which
     * ends the process.
     */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val handling = AtomicBoolean(false)
        val recorded = CountDownLatch(1)
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                if (handling.compareAndSet(false, true)) {
                    try {
                        // The crash file first: flushing the log may take its full wait.
                        runCatching { write(thread.name, error, log = logTail()) }
                        runCatching {
                            AppLog.e("Crash", "Chatter closed on thread ${thread.name}", error)
                            AppLog.flushNow()
                        }
                    } finally {
                        recorded.countDown()
                    }
                } else {
                    // A second thread crashing meanwhile would end the process before the first is written.
                    recorded.await(SECOND_CRASH_WAIT_MS, TimeUnit.MILLISECONDS)
                }
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    /** Records a crash and keeps only the last [KEPT]. [log] is the private log's last lines. */
    fun write(thread: String, error: Throwable, at: Long = System.currentTimeMillis(), log: List<String> = emptyList()) {
        save(at, format(at, thread, error, device, log))
    }

    /**
     * Records an end Android reported, like a hang: [kind] is [Crash.HANG] or [Crash.NATIVE],
     * [trace] what it said. [version] is the ended run's, which an update since may have changed.
     */
    fun writeExit(at: Long, kind: String, trace: String, version: String?, log: List<String>) {
        if (File(dir, "$PREFIX$at$SUFFIX").exists()) return
        save(at, formatExit(at, kind, trace, version, device, log))
    }

    private fun save(at: Long, text: String) {
        dir.mkdirs()
        // Renamed into place, so the list never reads half a file if the process ends while writing.
        val partial = File(dir, "$PREFIX$at$PARTIAL")
        partial.writeText(text)
        partial.renameTo(File(dir, "$PREFIX$at$SUFFIX"))
        files().dropLast(KEPT).forEach { it.delete() }
    }

    /** The most recent crash, or null. */
    fun latest(): Crash? = files().lastOrNull()?.let(::read)

    /** Newest first. */
    fun all(): List<Crash> = files().asReversed().mapNotNull(::read)

    fun delete(at: Long) {
        File(dir, "$PREFIX$at$SUFFIX").delete()
    }

    /** Also what a process ended mid-write left. */
    fun clear() {
        files().forEach { it.delete() }
        dir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(PARTIAL) }.orEmpty().forEach { it.delete() }
    }

    /**
     * The newest crash not yet offered for reporting, if it happened in the last [OFFER_WITHIN_MS]
     * and nothing was offered in the last [OFFER_PAUSE_MS]: a crash on every start should not ask
     * on every start.
     */
    fun unoffered(now: Long = System.currentTimeMillis()): Crash? {
        val crash = latest() ?: return null
        val (upTo, offeredAt) = offered()
        val pausing = offeredAt != null && now - offeredAt < OFFER_PAUSE_MS
        if (crash.at <= upTo || now - crash.at > OFFER_WITHIN_MS || pausing) return null
        return crash
    }

    /** No crash up to [at] is offered again, and none other for a while. */
    fun markOffered(at: Long, now: Long = System.currentTimeMillis()) {
        runCatching {
            dir.mkdirs()
            File(dir, OFFERED).writeText("${maxOf(at, offered().first)} $now")
        }
    }

    /** The newest crash offered, and when; "1731500000000 1731500100000" in the file. */
    private fun offered(): Pair<Long, Long?> = runCatching {
        val (upTo, offeredAt) = File(dir, OFFERED).readText().trim().split(' ').map { it.toLong() }
        upTo to offeredAt
    }.getOrDefault(0L to null)

    /** Cleaned again, since an older version may have written it. */
    private fun read(file: File): Crash? {
        val at = file.name.removePrefix(PREFIX).removeSuffix(SUFFIX).toLongOrNull() ?: return null
        return runCatching { Crash(at, clean(file.readText())) }.getOrNull()
    }

    /** Oldest first. */
    private fun files(): List<File> =
        dir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }.orEmpty()
            .sortedBy { it.name.removePrefix(PREFIX).removeSuffix(SUFFIX).toLongOrNull() ?: 0L }

    companion object {
        /** Enough to see whether it happens again, few enough that the folder stays under half a megabyte. */
        const val KEPT = 5

        private const val PREFIX = "crash-"
        private const val SUFFIX = ".txt"
        private const val PARTIAL = ".partial"
        private const val OFFERED = "offered"

        /** Older crashes are in the list under About, but not worth a question on start. */
        private const val OFFER_WITHIN_MS = 3 * 24 * 60 * 60 * 1000L
        private const val OFFER_PAUSE_MS = 24 * 60 * 60 * 1000L
        private const val SECOND_CRASH_WAIT_MS = 1_000L

        /** OkHttp's thread names carry a link; a long one would crowd the issue's link. */
        private const val MAX_THREAD_CHARS = 80

        /**
         * Compose stack traces run to hundreds of lines; the cause is at the top, and longer text
         * is awkward to paste into an issue.
         */
        private const val MAX_TRACE_CHARS = 12_000

        /** What the minute before a crash usually takes; a busy chat writes more, but the cause is near the end. */
        private const val LOG_LINES = 200

        /** HTTP lines can be long; this keeps one crash file under 50 kB. */
        private const val MAX_LOG_CHARS = 30_000

        private const val HIDDEN = "[hidden]"

        /** The private log's last lines, as they go into a report. */
        fun logTail(entries: List<AppLog.Entry> = AppLog.entries()): List<String> {
            val kept = ArrayDeque<String>()
            var chars = 0
            for (entry in entries.takeLast(LOG_LINES).asReversed()) {
                val line = LogReport.line(entry, full = false)
                chars += line.length + 1
                if (chars > MAX_LOG_CHARS) break
                kept.addFirst(line)
            }
            return kept
        }

        /**
         * Login secrets an error message may quote: a request's headers, the login redirect, the
         * chat's PASS line. The name stays, so the report still says what was there.
         */
        private val SECRETS = listOf(
            // The whole value, whatever scheme it names: "Authorization: Bearer …", "OAuth …".
            Regex("""(?i)\b((?:proxy-)?authorization["']?\s*[:=]\s*["']?)[^\r\n"',;}]+"""),
            Regex("""(?i)\b(bearer\s+)[A-Za-z0-9._~+/=-]+"""),
            Regex("""(?i)\b(oauth:)[A-Za-z0-9._~+/=-]+"""),
            // As URL parameter, form field or JSON: access_token=…, "refresh_token":"…".
            Regex("""(?i)\b((?:access_|refresh_|id_)?token|client_secret|password|passwd)(["']?\s*[:=]\s*["']?)[^\s&"',;}#]+"""),
        )

        /**
         * [text] cleaned line by line like the private log: no secrets, no links past their host,
         * no addresses. A crash goes into a public issue, and an error message can quote a link
         * with a channel's name. Line by line, so an error page cut off in one message does not
         * take the frames below along.
         */
        internal fun clean(text: String): String =
            ERROR_PAGE.replace(text) { it.groupValues[1] }.lines().joinToString("\n") { LogPrivacy.clean(it) }

        /**
         * An error page in an exception's message ("HTTP 404: <body>", see `HttpException`), up to
         * the frames below it. It can run over several lines and quote what was asked for.
         */
        private val ERROR_PAGE = Regex("""(?s)\b(HTTP \d{3}): .*?(?=\n\s*at |\nCaused by: |\n\s*Suppressed: |\z)""")

        /** [text] with the values [SECRETS] finds replaced. Internal for tests. */
        internal fun hideSecrets(text: String): String = SECRETS.fold(text) { result, secret ->
            secret.replace(result) { match -> match.groupValues.drop(1).joinToString("") + HIDDEN }
        }

        /** The crash as it goes into a bug report; see [Crash] for its parts. Internal for tests. */
        internal fun format(at: Long, thread: String, error: Throwable, device: DeviceInfo, log: List<String> = emptyList()): String {
            // printStackTrace includes causes and suppressed errors. Cleaned before it is cut, so a
            // cut cannot leave half a secret behind.
            val trace = clean(StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().trimEnd())
            // OkHttp names its threads after the link they load.
            val head = header(at, version = null, device, "Thread" to LogPrivacy.clean(thread).take(MAX_THREAD_CHARS))
            // Cut frame by frame, so every cause stays, the root one included.
            return head + CrashReport.shortTrace(trace, MAX_TRACE_CHARS) { it.length }.first + logPart(log)
        }

        /** An end Android reported, laid out like a crash; see [writeExit]. Internal for tests. */
        internal fun formatExit(at: Long, kind: String, trace: String, version: String?, device: DeviceInfo, log: List<String>): String =
            header(at, version, device, "Kind" to kind) + clean(trace).take(MAX_TRACE_CHARS).trimEnd() + logPart(log)

        /** The lines above the trace; [last] names what kind of crash it was. */
        private fun header(at: Long, version: String?, device: DeviceInfo, last: Pair<String, String>): String {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(at))
            return buildString {
                appendLine("Time: $time")
                appendLine("Chatter: ${version ?: "${device.appVersion} (${device.versionCode})"}")
                appendLine("Android: ${device.android} (API ${device.sdk})")
                appendLine("Device: ${device.device}")
                appendLine("${last.first}: ${last.second}")
                appendLine()
            }
        }

        private fun logPart(log: List<String>): String =
            if (log.isEmpty()) "\n" else Crash.LOG_HEADING + log.joinToString("") { it + "\n" }
    }
}
