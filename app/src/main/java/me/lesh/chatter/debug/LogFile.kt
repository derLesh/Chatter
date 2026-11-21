package me.lesh.chatter.debug

import java.io.BufferedWriter
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The log on the phone's storage, so the last [AppLog.KEPT] lines survive a restart and a crash.
 * One line per entry, its fields split by tabs. Everything runs on a thread of its own; lines are
 * flushed every [FLUSH_SECONDS] and at once on a crash. Lines the file lets go of move into
 * [archive], by day.
 */
internal class LogFile(private val file: File, private val archive: LogArchive? = null) {
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "AppLog file").apply { isDaemon = true }
    }
    private var writer: BufferedWriter? = null
    private var linesInFile = 0

    /** Reads what an earlier start left, then starts appending. [loaded] gets those entries, oldest first. */
    fun open(loaded: (List<AppLog.Entry>) -> Unit) = worker.execute {
        val all = runCatching { file.readLines() }.getOrDefault(emptyList())
        runCatching { archive?.add(all.dropLast(AppLog.KEPT)) }
        val old = all.takeLast(AppLog.KEPT)
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
            if (++linesInFile >= 2 * AppLog.KEPT) {
                val kept = current()
                archiveAllBut(kept.size)
                rewrite(kept)
            }
        }
    }

    fun clear() = worker.execute {
        rewrite(emptyList())
        runCatching { archive?.clear() }
    }

    /**
     * Everything written on [date], oldest first: what the archive holds and what the file still
     * does. Read on the file's thread, so no line moves between the two while they are read.
     */
    fun day(date: LocalDate): List<AppLog.Entry> = runCatching {
        worker.submit<List<AppLog.Entry>> {
            writer?.flush()
            val inFile = runCatching { file.readLines() }.getOrDefault(emptyList())
                .mapIndexedNotNull { i, line -> decode(line, number = i.toLong()) }
            val archive = archive ?: return@submit inFile
            // Numbered anew, as the archive and the file each count from zero.
            (archive.read(date) + inFile.filter { archive.dateOf(it.at) == date }).mapIndexed { i, e ->
                AppLog.Entry(i.toLong(), e.at, e.level, e.tag, e.thread, e.private, e.full)
            }
        }.get()
    }.getOrDefault(emptyList())

    /** Newest first, today included once anything was written today. */
    fun days(): List<LocalDate> = runCatching {
        worker.submit<List<LocalDate>> {
            val archive = archive ?: return@submit emptyList()
            val fileDays = runCatching { file.readLines() }.getOrDefault(emptyList())
                .mapNotNull { line -> line.substringBefore('\t').toLongOrNull()?.let(archive::dateOf) }.toSet()
            (archive.days() + fileDays).distinct().filter { archive.keeps(it) }.sortedDescending()
        }.get()
    }.getOrDefault(emptyList())

    /** Moves the file's lines older than its last [keep] into the archive, before the file is written anew. */
    private fun archiveAllBut(keep: Int) {
        val archive = archive ?: return
        runCatching {
            writer?.flush()
            val lines = file.readLines()
            archive.add(lines.dropLast(keep))
        }
    }

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
