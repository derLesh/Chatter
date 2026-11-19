package me.lesh.chatter.debug

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The lines the log has moved past, one gzip file per day ("2026-11-19.gz"), so a bug from
 * yesterday can still be looked into. Today and the [DAYS_BACK] days before are kept. Lines are
 * stored as [LogFile] writes them, private and full, and compress to about a tenth.
 *
 * Each [add] appends a gzip member of its own: gzip allows several in one file, and the file
 * never has to be read and written again. A day stops growing at [MAX_DAY_BYTES].
 */
internal class LogArchive(private val dir: File, private val zone: ZoneId = ZoneId.systemDefault()) {
    /** [lines] in [LogFile.encode]'s format, oldest first. */
    fun add(lines: List<String>, now: Long = System.currentTimeMillis()) {
        if (lines.isEmpty()) return
        dir.mkdirs()
        lines.groupBy { day(it) ?: today(now) }.forEach { (date, ofDay) ->
            val file = file(date)
            if (file.length() >= MAX_DAY_BYTES) return@forEach
            // Compressed in memory first, so an ended process leaves at most one member unfinished.
            val member = ByteArrayOutputStream().also { bytes ->
                GZIPOutputStream(bytes).bufferedWriter().use { w -> ofDay.forEach { w.appendLine(it) } }
            }.toByteArray()
            file.appendBytes(member)
        }
        prune(now)
    }

    /** Newest first. */
    fun days(): List<LocalDate> = files().map { (date, _) -> date }.sortedDescending()

    /** The day's lines, oldest first. A member cut short by an ended process ends the reading. */
    fun read(date: LocalDate): List<AppLog.Entry> {
        val file = file(date)
        if (!file.exists()) return emptyList()
        val lines = mutableListOf<String>()
        try {
            GZIPInputStream(file.inputStream()).bufferedReader().use { reader ->
                while (true) lines += reader.readLine() ?: break
            }
        } catch (_: IOException) {
            // What was read before the damage is still good.
        }
        return lines.mapIndexedNotNull { i, line -> LogFile.decode(line, number = i.toLong()) }
    }

    fun clear() {
        files().forEach { (_, file) -> file.delete() }
    }

    /** Whether [date] is one of the days kept. */
    fun keeps(date: LocalDate, now: Long = System.currentTimeMillis()): Boolean = date >= today(now).minusDays(DAYS_BACK)

    fun dateOf(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

    private fun prune(now: Long) {
        files().filter { (date, _) -> !keeps(date, now) }.forEach { (_, file) -> file.delete() }
    }

    private fun files(): List<Pair<LocalDate, File>> =
        dir.listFiles { f -> f.name.endsWith(SUFFIX) }.orEmpty().mapNotNull { file ->
            runCatching { LocalDate.parse(file.name.removeSuffix(SUFFIX)) }.getOrNull()?.let { it to file }
        }

    private fun file(date: LocalDate) = File(dir, "$date$SUFFIX")

    /** The day a line was written on, from its first field, the time in milliseconds. */
    private fun day(line: String): LocalDate? = line.substringBefore('\t').toLongOrNull()?.let(::dateOf)

    private fun today(now: Long) = dateOf(now)

    companion object {
        /** With today, the last four days: a bug from the weekend is still there on Monday. */
        const val DAYS_BACK = 3L

        /** About 30,000 to 60,000 lines. A busier day keeps its first lines and drops the rest. */
        const val MAX_DAY_BYTES = 1_000_000L

        private const val SUFFIX = ".gz"
    }
}
