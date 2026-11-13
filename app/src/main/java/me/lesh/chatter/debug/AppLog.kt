package me.lesh.chatter.debug

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.lesh.chatter.BuildConfig
import me.lesh.chatter.crash.CrashLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the app does, for the log under Settings → Debug, and for logcat. Each line is kept twice:
 * private, cleaned by [LogPrivacy] and with names marked by [hidden] left out, and full, with them.
 * Login secrets are taken out of both. The last [KEPT] lines are kept on the phone by [keepIn], so
 * they outlast a restart; they leave it only when the user copies, saves or reports them.
 *
 * A single object rather than part of `AppContainer`, so code built before it, and tests, can log.
 */
object AppLog {
    /** [Verbose] is for what happens many times a minute: every image, every chat command. */
    enum class Level(val letter: Char, val priority: Int) {
        Verbose('V', Log.VERBOSE), Debug('D', Log.DEBUG), Info('I', Log.INFO), Warn('W', Log.WARN), Error('E', Log.ERROR),
    }

    /** One line. [number] counts every line written since the start, so a cleared log goes on from it. */
    class Entry(
        val number: Long,
        val at: Long,
        val level: Level,
        val tag: String,
        val thread: String,
        val private: String,
        val full: String,
    ) {
        fun message(full: Boolean) = if (full) this.full else private
    }

    /** An evening of a busy session, and a megabyte or two at most. */
    const val KEPT = 5_000

    private val lock = Any()
    private val entries = ArrayDeque<Entry>(KEPT)
    private var written = 0L

    private val _revision = MutableStateFlow(0L)
    /** Changes with every line written and with [clear], for a screen showing the log. */
    val revision: StateFlow<Long> = _revision

    @Volatile private var file: LogFile? = null

    private val channelNames = ConcurrentHashMap<String, String>()
    private val channelsNamed = AtomicInteger()

    fun v(tag: String, message: String, error: Throwable? = null) = write(Level.Verbose, tag, message, error)
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

    /**
     * Keeps the log in [file] too, and brings back what an earlier start left there. Call it first
     * thing, so the start itself is in the file.
     */
    fun keepIn(file: File) {
        val log = LogFile(file)
        this.file = log
        log.open { earlier ->
            synchronized(lock) {
                earlier.takeLast(KEPT - entries.size).asReversed().forEach(entries::addFirst)
            }
            _revision.value++
        }
    }

    /** Writes out what is still buffered; for a crash, before the process ends. */
    fun flushNow() {
        file?.flushNow()
    }

    /** Oldest first. */
    fun entries(): List<Entry> = synchronized(lock) { entries.toList() }

    fun clear() {
        synchronized(lock) { entries.clear() }
        file?.clear()
        _revision.value++
    }

    private fun write(level: Level, tag: String, message: String, error: Throwable?) {
        val raw = if (error == null) message else "$message: ${describe(error)}"
        val private = LogPrivacy.clean(MARKED.replace(raw) { it.groupValues[2] })
        val full = CrashLog.hideSecrets(MARKED.replace(raw) { it.groupValues[1] })
        // Only the developer's own build tells logcat the names. runCatching: a stub in unit tests.
        runCatching { Log.println(level.priority, tag, if (BuildConfig.DEBUG) full else private) }
        val entry = synchronized(lock) {
            if (entries.size == KEPT) entries.removeFirst()
            written++
            Entry(written, System.currentTimeMillis(), level, tag, threadName(), private, full).also(entries::addLast)
        }
        file?.append(entry) { entries() }
        _revision.value++
    }

    /** OkHttp names its threads after the link it loads; cleaned like a message. */
    private fun threadName(): String = LogPrivacy.clean(Thread.currentThread().name).take(40)

    /**
     * The error's kind and message, and the first line of Chatter's own code it passed: enough to
     * find it, without a stack trace that runs to pages.
     */
    private fun describe(error: Throwable): String {
        // The log's own code (the HTTP interceptor) only passes errors on; the frame that matters is the caller's.
        val frame = error.stackTrace.firstOrNull { it.className.startsWith("me.lesh.chatter") && !it.className.startsWith(HttpLog::class.java.name) }
        val where = frame?.let { " (at ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber})" }.orEmpty()
        // The whole chain: a dropped socket is often "SocketException" caused by an errno that says why.
        val causes = generateSequence(error.cause) { it.cause?.takeIf { next -> next !== it } }.take(MAX_CAUSES)
            .joinToString("") { "; caused by ${it.javaClass.simpleName}: ${it.message}" }
        return "${error.javaClass.name}: ${error.message}$where$causes"
    }

    private const val MAX_CAUSES = 5

    // Control characters, so they never clash with what a message says.
    private const val START = '\u0002'
    private const val SPLIT = '\u001F'
    private const val END = '\u0003'
    private val MARKED = Regex("$START([^$SPLIT$END]*)$SPLIT([^$END]*)$END")
}
