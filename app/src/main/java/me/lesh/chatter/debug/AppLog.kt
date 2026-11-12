package me.lesh.chatter.debug

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.lesh.chatter.BuildConfig
import me.lesh.chatter.crash.CrashLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the app does, for the log under Settings → Debug, and for logcat. Each line is kept twice:
 * private, cleaned by [LogPrivacy] and with names marked by [hidden] left out, and full, with them.
 * Login secrets are taken out of both. Kept in memory only: gone when the process ends, and off
 * the phone only when the user copies, saves or reports it.
 *
 * A single object rather than part of `AppContainer`, so code built before it, and tests, can log.
 */
object AppLog {
    enum class Level(val letter: Char, val priority: Int) {
        Debug('D', Log.DEBUG), Info('I', Log.INFO), Warn('W', Log.WARN), Error('E', Log.ERROR),
    }

    /** One line. [number] counts every line written since the start, so a cleared log goes on from it. */
    class Entry(val number: Long, val at: Long, val level: Level, val tag: String, val private: String, val full: String) {
        fun message(full: Boolean) = if (full) this.full else private
    }

    /** About an evening of a busy session, and a few hundred kilobytes at most. */
    const val KEPT = 2_000

    private val lock = Any()
    private val entries = ArrayDeque<Entry>(KEPT)
    private var written = 0L

    private val _revision = MutableStateFlow(0L)
    /** Changes with every line written and with [clear], for a screen showing the log. */
    val revision: StateFlow<Long> = _revision

    private val channelNames = ConcurrentHashMap<String, String>()
    private val channelsNamed = AtomicInteger()

    fun d(tag: String, message: String, error: Throwable? = null) = write(Level.Debug, tag, message, error)
    fun i(tag: String, message: String, error: Throwable? = null) = write(Level.Info, tag, message, error)
    fun w(tag: String, message: String, error: Throwable? = null) = write(Level.Warn, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = write(Level.Error, tag, message, error)

    /**
     * [text] for the full log only; the private one shows [instead]. Put it into a message:
     * `"Joined ${AppLog.hidden(name, "a channel")}"`.
     */
    fun hidden(text: String, instead: String = ""): String = "$START$text$SPLIT$instead$END"

    /**
     * A channel's name for the full log, and for the private one a stand-in that stays the same
     * until the app restarts: "channel 3". The private log can then follow one channel without
     * saying which.
     */
    fun channel(login: String): String {
        val name = login.lowercase()
        return hidden("#$name", channelNames.computeIfAbsent(name) { "channel ${channelsNamed.incrementAndGet()}" })
    }

    fun user(login: String): String = hidden(login, "a user")

    /** Oldest first. */
    fun entries(): List<Entry> = synchronized(lock) { entries.toList() }

    fun clear() {
        synchronized(lock) { entries.clear() }
        _revision.value++
    }

    private fun write(level: Level, tag: String, message: String, error: Throwable?) {
        val raw = if (error == null) message else "$message: ${describe(error)}"
        val private = LogPrivacy.clean(MARKED.replace(raw) { it.groupValues[2] })
        val full = CrashLog.hideSecrets(MARKED.replace(raw) { it.groupValues[1] })
        // Only the developer's own build tells logcat the names. runCatching: a stub in unit tests.
        runCatching { Log.println(level.priority, tag, if (BuildConfig.DEBUG) full else private) }
        synchronized(lock) {
            if (entries.size == KEPT) entries.removeFirst()
            written++
            entries.addLast(Entry(written, System.currentTimeMillis(), level, tag, private, full))
        }
        _revision.value++
    }

    /**
     * The error's kind and message, and the first line of Chatter's own code it passed: enough to
     * find it, without a stack trace that runs to pages.
     */
    private fun describe(error: Throwable): String {
        val frame = error.stackTrace.firstOrNull { it.className.startsWith("me.lesh.chatter") }
        val where = frame?.let { " (at ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber})" }.orEmpty()
        val cause = error.cause?.takeIf { it !== error }?.let { "; caused by ${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
        return "${error.javaClass.simpleName}: ${error.message}$where$cause"
    }

    // Control characters, so they never clash with what a message says.
    private const val START = '\u0002'
    private const val SPLIT = '\u001F'
    private const val END = '\u0003'
    private val MARKED = Regex("$START([^$SPLIT$END]*)$SPLIT([^$END]*)$END")
}
