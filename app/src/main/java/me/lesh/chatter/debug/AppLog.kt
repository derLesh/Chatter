package me.lesh.chatter.debug

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the app does, for the log under Settings → Debug, and for logcat. Every line is cleaned by
 * [LogPrivacy] before it is kept, and lives in memory only: it is gone when the process ends and
 * leaves the phone only when the user copies, saves or reports it.
 *
 * A single object rather than part of `AppContainer`, so code built before it, and tests, can log.
 */
object AppLog {
    enum class Level(val letter: Char, val priority: Int) {
        Debug('D', Log.DEBUG), Info('I', Log.INFO), Warn('W', Log.WARN), Error('E', Log.ERROR),
    }

    /** One line. [number] counts every line written since the start, so a cleared log goes on from it. */
    class Entry(val number: Long, val at: Long, val level: Level, val tag: String, val message: String)

    /** About an evening of a busy session, and a few hundred kilobytes at most. */
    const val KEPT = 2_000

    private val lock = Any()
    private val entries = ArrayDeque<Entry>(KEPT)
    private var written = 0L

    private val _lastNumber = MutableStateFlow(0L)
    /** The number of the newest line; changes with every line, for a screen showing the log. */
    val lastNumber: StateFlow<Long> = _lastNumber

    private val channelNames = ConcurrentHashMap<String, String>()
    private val channelsNamed = AtomicInteger()

    fun d(tag: String, message: String, error: Throwable? = null) = write(Level.Debug, tag, message, error)
    fun i(tag: String, message: String, error: Throwable? = null) = write(Level.Info, tag, message, error)
    fun w(tag: String, message: String, error: Throwable? = null) = write(Level.Warn, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = write(Level.Error, tag, message, error)

    /**
     * A stand-in for a channel's name, the same for the same channel until the app restarts:
     * "channel 3". The log can then follow one channel without saying which.
     */
    fun channel(login: String): String =
        channelNames.computeIfAbsent(login.lowercase()) { "channel ${channelsNamed.incrementAndGet()}" }

    /** Oldest first. */
    fun entries(): List<Entry> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) { entries.clear() }

    private fun write(level: Level, tag: String, message: String, error: Throwable?) {
        val text = LogPrivacy.clean(if (error == null) message else "$message: ${describe(error)}")
        // runCatching: in unit tests android.util.Log is a stub.
        runCatching { Log.println(level.priority, tag, text) }
        val number = synchronized(lock) {
            if (entries.size == KEPT) entries.removeFirst()
            written++
            entries.addLast(Entry(written, System.currentTimeMillis(), level, tag, text))
            written
        }
        _lastNumber.value = number
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
}
