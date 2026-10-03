package dev.chatter.app.crash

import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What a bug report needs to know about where Chatter ran, besides what went wrong. */
data class DeviceInfo(val appVersion: String, val versionCode: Int, val android: String, val sdk: Int, val device: String) {
    companion object {
        fun current(appVersion: String, versionCode: Int) = DeviceInfo(
            appVersion = appVersion,
            versionCode = versionCode,
            android = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            // The model alone is often a code name ("GKWS6"); the maker in front makes it a phone.
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }
}

/** One crash as it was written down: when, and the text a bug report gets. */
class Crash(val at: Long, val text: String)

/**
 * The last few times Chatter crashed, kept in its own storage.
 *
 * Without this a crash leaves its trace in logcat only, which nobody using the app can reach, and
 * a bug report says "it closed" and nothing more. There is no crash reporting service, and there
 * will not be one: what is written here stays on the phone until the user pastes it into a report
 * themselves, which is what the privacy policy promises.
 */
class CrashLog(private val dir: File, private val device: DeviceInfo) {
    /**
     * Writes every uncaught exception down before the process goes, then hands it on to the
     * handler that was there before — Android's, which shows the dialog and ends the process.
     */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Whatever goes wrong while writing must not keep Android from ending the process.
            runCatching { write(thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Writes a crash down, and lets go of all but the last [KEPT]. */
    fun write(thread: String, error: Throwable, at: Long = System.currentTimeMillis()) {
        dir.mkdirs()
        File(dir, "$PREFIX$at$SUFFIX").writeText(format(at, thread, error, device))
        files().dropLast(KEPT).forEach { it.delete() }
    }

    /** The most recent crash, or null if Chatter has not crashed (or not since it was cleared). */
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
         * A stack trace from deep inside Compose runs to hundreds of lines. The top is where the
         * cause is; past this the text would only be too long to paste into an issue.
         */
        private const val MAX_TRACE_CHARS = 12_000

        /** The crash as a bug report gets it. Internal for tests. */
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
