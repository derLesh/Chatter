import java.time.LocalDate
import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.changelog)
}

// ---- Releases ----------------------------------------------------------------------------------
//
// Changesets, the way the JavaScript tool of the same name works them: every change a user can
// notice leaves its own file in pending-changelog/ instead of everyone editing one shared list, so
// two branches never collide over the changelog. A release folds the pending files into one
// CHANGELOG.md entry and moves the version by the largest level among them. AGENTS.md has the
// rules; pending-changelog/README.md has the file format.
//
//   ./gradlew changelogStatus   what is pending, and which version it would release
//   ./gradlew checkChangelog    are the pending entries well formed?
//   ./gradlew releaseVersion    bump the version, write the changelog, clear pending-changelog/

/** How far one entry moves the version. Smallest first, so the largest pending one wins. */
enum class Level { PATCH, MINOR, MAJOR }

/** Files in pending-changelog/ that are notes for us rather than entries for the changelog. */
val notEntries = listOf(".gitkeep", "README.md")

val pendingDirFile = layout.projectDirectory.dir("pending-changelog").asFile
val versionFile = layout.projectDirectory.file("version.properties").asFile
val changelogMd = layout.projectDirectory.file("CHANGELOG.md").asFile

/**
 * Every pending line as the file it came from and its text. Blank lines go the same way the plugin
 * drops them when it writes the changelog, so a stray empty line at the end of a file is harmless.
 */
val pendingLines: List<Pair<String, String>> = pendingDirFile.listFiles()
    .orEmpty()
    .filter { it.isFile && it.name !in notEntries }
    .sortedBy { it.name }
    .flatMap { file -> file.readLines().map { file.name to it } }
    .filter { (_, line) -> line.isNotBlank() }

fun levelOf(line: String): Level? = Level.entries.firstOrNull { line.startsWith("${it.name.lowercase()}: ") }

/**
 * The pending entries that name their level. One that does not is left to checkChangelog, which
 * says exactly what is wrong with it: a typo in a changelog entry must not break every build.
 */
val pendingEntries: List<Pair<Level, String>> = pendingLines.mapNotNull { (_, line) ->
    levelOf(line)?.let { it to line }
}

/** The lines checkChangelog is going to reject, so changelogStatus can point at them early. */
val malformedEntries: List<String> = pendingLines
    .filter { (_, line) -> levelOf(line) == null }
    .map { (file, line) -> "  ?      $line  [$file]" }

val released = Properties().apply { versionFile.inputStream().use { load(it) } }
val releasedVersion: String = requireNotNull(released.getProperty("version")) { "version.properties has no version" }
val releasedCode: Int = requireNotNull(released.getProperty("versionCode")) { "version.properties has no versionCode" }.toInt()

/** What the pending entries add up to, or the released version when nothing is pending. */
val nextVersion: String = pendingEntries.maxOfOrNull { it.first }?.let { level ->
    val parts = releasedVersion.split('.').mapNotNull { it.toIntOrNull() }
    require(parts.size == 3) { "version.properties: \"$releasedVersion\" is not a major.minor.patch version" }
    val (major, minor, patch) = parts
    when (level) {
        Level.MAJOR -> "${major + 1}.0.0"
        Level.MINOR -> "$major.${minor + 1}.0"
        Level.PATCH -> "$major.$minor.${patch + 1}"
    }
} ?: releasedVersion

/**
 * Everything above the first release heading is the changelog's own header. New entries go right
 * after it, which is what puts the newest release on top.
 */
val changelogHeaderLines: Int = changelogMd.readLines()
    .indexOfFirst { it.startsWith("## ") }
    .let { if (it < 0) changelogMd.readLines().size else it }

changelog {
    pendingChangelogDir.set(layout.projectDirectory.dir("pending-changelog"))
    changelogFile.set(layout.projectDirectory.file("CHANGELOG.md"))
    ignoreFiles.set(notEntries)

    // The level travels with the entry, which is what lets a release work out its own version, and
    // what lets the app group a release into what is new and what was fixed. Unlike the changelog
    // itself, these rules also see blank lines, so every one of them lets a blank line pass.
    addRule("must start with \"patch: \", \"minor: \" or \"major: \"") { line ->
        line.isBlank() || Level.entries.any { line.startsWith("${it.name.lowercase()}: ") }
    }
    addRule("must say something after its level") { line ->
        line.isBlank() || line.substringAfter(": ").isNotBlank()
    }
    addRule("must read as a sentence, so it cannot end with a dot") { line ->
        line.isBlank() || !line.endsWith(".")
    }
    addRule("must stay under 120 characters to read well on a phone") { it.length <= 120 }

    commit {
        prefix = "## $nextVersion — ${LocalDate.now()}"
        entryPrefix = "- "
        // An empty line below the entries, so the release before this one keeps its own paragraph.
        postfix = ""
        insertAtLine = changelogHeaderLines
    }
}

// A task action must not reach back into the build script, or the configuration cache cannot
// store it. Every action below therefore copies what it needs into a local first.
val pendingCount = pendingEntries.size
val pendingReport = pendingEntries.map { (level, line) ->
    "  ${level.name.lowercase().padEnd(5)}  ${line.substringAfter(": ")}"
}
val nextCode = if (pendingEntries.isEmpty()) releasedCode else releasedCode + 1
val versionSummary = "$releasedVersion -> $nextVersion (versionCode $releasedCode -> $nextCode)"
val hasPending = pendingEntries.isNotEmpty()

tasks.register("changelogStatus") {
    group = "changelog"
    description = "Lists the pending changelog entries and the version they would release."
    val count = pendingCount
    val report = pendingReport
    val malformed = malformedEntries
    val summary = versionSummary
    val current = releasedVersion
    val currentCode = releasedCode
    doLast {
        if (count == 0) {
            logger.lifecycle("Nothing pending. Chatter stays at $current (versionCode $currentCode).")
        } else {
            logger.lifecycle("$summary, from $count pending entr${if (count == 1) "y" else "ies"}:")
            report.forEach { logger.lifecycle(it) }
        }
        if (malformed.isNotEmpty()) {
            logger.lifecycle("")
            logger.lifecycle("${malformed.size} line(s) name no level, so no release counts them:")
            malformed.forEach { logger.lifecycle(it) }
            logger.lifecycle("Run \"./gradlew checkChangelog\" to see what is wrong with them.")
        }
    }
}

/** Writes the version the app builds against. The pending entries are what decide it. */
tasks.register<WriteProperties>("bumpVersion") {
    group = "changelog"
    description = "Moves version.properties by the largest level among the pending entries."
    destinationFile = layout.projectDirectory.file("version.properties")
    comment = "Written by \"./gradlew releaseVersion\". Put the change in pending-changelog/, not here."
    property("version", nextVersion)
    property("versionCode", nextCode)
    val pending = hasPending
    onlyIf { pending }
}

tasks.named("commitChangelog") {
    val pending = hasPending
    onlyIf { pending }
}

tasks.register("releaseVersion") {
    group = "changelog"
    description = "Folds the pending entries into CHANGELOG.md and bumps the version."
    dependsOn("checkChangelog", "bumpVersion", "commitChangelog")
    val count = pendingCount
    val summary = versionSummary
    val current = releasedVersion
    doLast {
        if (count == 0) {
            logger.lifecycle("Nothing pending in pending-changelog/, so $current stays as it is.")
        } else {
            logger.lifecycle("Released $summary from $count entr${if (count == 1) "y" else "ies"}.")
        }
    }
}
