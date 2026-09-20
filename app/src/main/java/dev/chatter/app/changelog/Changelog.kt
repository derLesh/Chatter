package dev.chatter.app.changelog

/** A version the way the changelog writes it: major.minor.patch. */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

    override fun toString(): String = "$major.$minor.$patch"

    /**
     * Whether the step from here to [other] is only a fix release. Those are not worth putting a
     * sheet in front of anyone, so the app keeps quiet about them until a bigger release lands.
     */
    fun isOnlyAFixAwayFrom(other: Version): Boolean = other.major == major && other.minor == minor

    companion object {
        /** Parses "1.2.3". Anything else — a missing value, a suffix — is not a version. */
        fun parse(text: String): Version? {
            val parts = text.trim().split('.')
            if (parts.size != 3) return null
            val numbers = parts.map { part -> part.trim().toIntOrNull() ?: return null }
            return Version(numbers[0], numbers[1], numbers[2])
        }
    }
}

/** How far a change moved the version, which is how the app groups and labels it. */
enum class Level { Major, Minor, Patch }

/** The changes of one release that share a level, or the ones that name none ([level] null). */
data class ChangeGroup(val level: Level?, val entries: List<String>)

/** One released version of Chatter and everything it changed. */
data class Release(val version: Version, val date: String, val groups: List<ChangeGroup>)

/**
 * Reads the CHANGELOG.md that `./gradlew releaseVersion` writes, which is the very same file the
 * repository shows on GitHub — the app ships it as an asset instead of keeping a second copy.
 *
 * Only three shapes carry meaning: `## <version> — <date>` opens a release, `- <level>: <text>` is
 * one change, and everything else is prose the app skips, so the file can keep its own header.
 */
object ChangelogParser {
    fun parse(markdown: String): List<Release> {
        val releases = mutableListOf<Release>()
        var version: Version? = null
        var date = ""
        var changes = mutableListOf<Pair<Level?, String>>()

        /** Closes off the release being read; one without any changes is not worth showing. */
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

    /** "0.3.0 — 2026-09-20", where the date is optional and may be joined by any kind of dash. */
    private val HEADING = Regex("""(\d+\.\d+\.\d+)(?:\s*[—–-]\s*(.*))?""")

    /** Biggest news first, with the changes that name no level last. */
    private val GROUP_ORDER = listOf(Level.Major, Level.Minor, Level.Patch, null)
}
