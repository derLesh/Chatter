package dev.chatter.app.crash

import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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

/** One recorded crash: when, and the text for the bug report. */
class Crash(val at: Long, val text: String)

/**
 * The last few crashes, stored in the app's own files. Without this the only trace is logcat, which
 * users cannot reach. Nothing is sent anywhere; the user pastes it into a report, as the privacy
 * policy says.
 */
class CrashLog(private val dir: File, private val device: DeviceInfo) {
    /**
     * Records every uncaught exception, then passes it to the previous handler (Android's), which
     * ends the process.
     */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // A failure here must not keep the process from ending.
            runCatching { write(thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Records a crash and keeps only the last [KEPT]. */
    fun write(thread: String, error: Throwable, at: Long = System.currentTimeMillis()) {
        dir.mkdirs()
        File(dir, "$PREFIX$at$SUFFIX").writeText(format(at, thread, error, device))
        files().dropLast(KEPT).forEach { it.delete() }
    }

    /** The most recent crash, or null. */
    fun latest(): Crash? {
        val file = files().lastOrNull() ?: return null
        val at = file.name.removePrefix(PREFIX).removeSuffix(SUFFIX).toLongOrNull() ?: return null
        return runCatching { Crash(at, file.readText()) }.getOrNull()
    }

    /** Oldest first. */
    private fun files(): List<File> =
        dir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }.orEmpty()
            .sortedBy { it.name.removePrefix(PREFIX).removeSuffix(SUFFIX).toLongOrNull() ?: 0L }

    companion object {
        /** Enough to see whether it happens again, few enough that the folder never grows. */
        const val KEPT = 3

        private const val PREFIX = "crash-"
        private const val SUFFIX = ".txt"

        /**
         * Compose stack traces run to hundreds of lines; the cause is at the top, and longer text
         * is awkward to paste into an issue.
         */
        private const val MAX_TRACE_CHARS = 12_000

        /** The crash as it goes into a bug report. Internal for tests. */
        internal fun format(at: Long, thread: String, error: Throwable, device: DeviceInfo): String {
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().trimEnd()
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(at))
            return buildString {
                appendLine("Time: $time")
                appendLine("Chatter: ${device.appVersion} (${device.versionCode})")
                appendLine("Android: ${device.android} (API ${device.sdk})")
                appendLine("Device: ${device.device}")
                appendLine("Thread: $thread")
                appendLine()
                if (trace.length <= MAX_TRACE_CHARS) append(trace)
                else append(trace, 0, MAX_TRACE_CHARS).append("\n\t… (cut)")
                appendLine()
            }
        }
    }
}
