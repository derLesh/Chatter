package dev.chatter.app.changelog

/** A version as the changelog writes it: major.minor.patch. */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

    override fun toString(): String = "$major.$minor.$patch"

    /** Whether going to [other] is a patch release only. Those get no "what's new" sheet. */
    fun isOnlyAFixAwayFrom(other: Version): Boolean = other.major == major && other.minor == minor

    companion object {
        /** Parses "1.2.3"; anything else, suffixes included, is null. */
        fun parse(text: String): Version? {
            val parts = text.trim().split('.')
            if (parts.size != 3) return null
            val numbers = parts.map { part -> part.trim().toIntOrNull() ?: return null }
            return Version(numbers[0], numbers[1], numbers[2])
        }
    }
}

/** How far a change moved the version; used to group and label entries. */
enum class Level { Major, Minor, Patch }

/** The changes of a release with one level, or those without a level ([level] null). */
data class ChangeGroup(val level: Level?, val entries: List<String>)

/** One release and its changes. */
data class Release(val version: Version, val date: String, val groups: List<ChangeGroup>)

/**
 * Reads CHANGELOG.md as `./gradlew releaseVersion` writes it; the app ships that file as an asset.
 *
 * `## <version> — <date>` opens a release and `- <level>: <text>` is one change. Everything else is
 * skipped, so the file can have its own header.
 */
object ChangelogParser {
    fun parse(markdown: String): List<Release> {
        val releases = mutableListOf<Release>()
        var version: Version? = null
        var date = ""
        var changes = mutableListOf<Pair<Level?, String>>()

        /** Ends the current release; one without changes is dropped. */
        fun finish() {
            val current = version
            if (current != null && changes.isNotEmpty()) {
                val read = changes
                val groups = GROUP_ORDER.mapNotNull { level ->
                    val entries = read.filter { it.first == level }.map { it.second }
                    if (entries.isEmpty()) null else ChangeGroup(level, entries)
                }
                releases += Release(current, date, groups)
            }
            version = null
            changes = mutableListOf()
        }

        markdown.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("## ") -> {
                    finish()
                    val heading = HEADING.matchEntire(line.removePrefix("## ").trim())
                    version = heading?.let { Version.parse(it.groupValues[1]) }
                    date = heading?.groupValues?.get(2)?.trim().orEmpty()
                }
                line.startsWith("- ") && version != null -> {
                    val entry = line.removePrefix("- ").trim()
                    val level = Level.entries.firstOrNull {
                        entry.startsWith("${it.name.lowercase()}: ", ignoreCase = true)
                    }
                    changes += level to if (level == null) entry else entry.substringAfter(": ").trim()
                }
            }
        }
        finish()
        return releases
    }

    /** "0.3.0 — 2026-09-20"; the date is optional and any dash works. */
    private val HEADING = Regex("""(\d+\.\d+\.\d+)(?:\s*[—–-]\s*(.*))?""")

    /** Biggest changes first, entries without a level last. */
    private val GROUP_ORDER = listOf(Level.Major, Level.Minor, Level.Patch, null)
}
