package me.lesh.chatter.debug

import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The log on the phone's storage, so the last [AppLog.KEPT] lines survive a restart and a crash.
 * One line per entry, its fields split by tabs. Everything runs on a thread of its own; lines are
 * flushed every [FLUSH_SECONDS] and at once on a crash.
 */
internal class LogFile(private val file: File) {
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "AppLog file").apply { isDaemon = true }
    }
    private var writer: BufferedWriter? = null
    private var linesInFile = 0

    /** Reads what an earlier start left, then starts appending. [loaded] gets those entries, oldest first. */
    fun open(loaded: (List<AppLog.Entry>) -> Unit) = worker.execute {
        val old = runCatching { file.readLines() }.getOrDefault(emptyList()).takeLast(AppLog.KEPT)
        // Numbered below zero, so they never share a number with a line written since.
        loaded(old.mapIndexedNotNull { i, line -> decode(line, number = i.toLong() - old.size) })
        linesInFile = old.size
        file.parentFile?.mkdirs()
        writer = runCatching { file.bufferedWriter(bufferSize = 16_384).also { w -> old.forEach { w.appendLine(it) } } }.getOrNull()
        worker.scheduleWithFixedDelay({ runCatching { writer?.flush() } }, FLUSH_SECONDS, FLUSH_SECONDS, TimeUnit.SECONDS)
    }

    /** [current] is asked for when the file has grown to twice what is kept, to write it anew. */
    fun append(entry: AppLog.Entry, current: () -> List<AppLog.Entry>) = worker.execute {
        val w = writer ?: return@execute
        runCatching {
            w.appendLine(encode(entry))
            if (++linesInFile >= 2 * AppLog.KEPT) rewrite(current())
        }
    }

    fun clear() = worker.execute { rewrite(emptyList()) }

    /** Waits a moment for the lines still in the buffer; for a crash, before the process ends. */
    fun flushNow() {
        runCatching { worker.submit { writer?.flush() }.get(500, TimeUnit.MILLISECONDS) }
    }

    private fun rewrite(entries: List<AppLog.Entry>) {
        runCatching { writer?.close() }
        writer = runCatching {
            file.bufferedWriter(bufferSize = 16_384).also { w -> entries.forEach { w.appendLine(encode(it)) } }
        }.getOrNull()
        linesInFile = entries.size
    }

    internal companion object {
        private const val FLUSH_SECONDS = 2L

        fun encode(e: AppLog.Entry): String =
            listOf(e.at.toString(), e.level.letter.toString(), e.tag, e.thread, e.private, e.full).joinToString("\t") { escape(it) }

        fun decode(line: String, number: Long): AppLog.Entry? {
            val fields = line.split('\t')
            if (fields.size != 6) return null
            val (at, letter, tag, thread, private) = fields.map(::unescape)
            val level = AppLog.Level.entries.firstOrNull { it.letter.toString() == letter } ?: return null
            return AppLog.Entry(number, at.toLongOrNull() ?: return null, level, tag, thread, private, unescape(fields[5]))
        }

        private fun escape(text: String) = buildString(text.length) {
            text.forEach { c ->
                when (c) {
                    '\\' -> append("\\\\")
                    '\t' -> append("\\t")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(c)
                }
            }
        }

        private fun unescape(text: String) = buildString(text.length) {
            var i = 0
            while (i < text.length) {
                val c = text[i++]
                if (c != '\\' || i == text.length) {
                    append(c)
                    continue
                }
                when (val next = text[i++]) {
                    't' -> append('\t')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    else -> append(next)
                }
            }
        }
    }
}
