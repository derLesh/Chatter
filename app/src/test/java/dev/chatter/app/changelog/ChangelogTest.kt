package dev.chatter.app.changelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    @Test
    fun `parses a three part version`() {
        assertEquals(Version(1, 2, 3), Version.parse("1.2.3"))
        assertEquals(Version(0, 10, 0), Version.parse(" 0.10.0 "))
    }

    @Test
    fun `refuses anything that is not a version`() {
        assertNull(Version.parse("1.2"))
        assertNull(Version.parse("1.2.3.4"))
        assertNull(Version.parse("1.2.x"))
        assertNull(Version.parse(""))
    }

    @Test
    fun `orders by major then minor then patch`() {
        assertTrue(Version(1, 0, 0) > Version(0, 99, 99))
        assertTrue(Version(0, 3, 0) > Version(0, 2, 9))
        assertTrue(Version(0, 2, 10) > Version(0, 2, 9))
    }

    @Test
    fun `tells a fix release from a bigger one`() {
        // Only the patch moved, so the app should stay quiet about it.
        assertTrue(Version(0, 2, 0).isOnlyAFixAwayFrom(Version(0, 2, 5)))
        assertTrue(!Version(0, 2, 0).isOnlyAFixAwayFrom(Version(0, 3, 0)))
        assertTrue(!Version(0, 2, 0).isOnlyAFixAwayFrom(Version(1, 0, 0)))
    }
}

class ChangelogParserTest {
    /** Exactly the shape "./gradlew releaseVersion" writes, header and all. */
    private val changelog = """
        # Changelog

        Every release of Chatter, newest first. Written from the entries in `pending-changelog/`.

        ## 0.3.0 — 2026-09-20
        - minor: Show the changelog in the settings and after an update
        - patch: Keep the emote picker from jumping while it loads
        - major: Drop the code login

        ## 0.2.0 — 2026-09-19
        - minor: Everything Chatter could do before it started keeping a changelog

    """.trimIndent()

    @Test
    fun `reads every release, newest first`() {
        val releases = ChangelogParser.parse(changelog)
        assertEquals(2, releases.size)
        assertEquals(Version(0, 3, 0), releases[0].version)
        assertEquals("2026-09-20", releases[0].date)
        assertEquals(Version(0, 2, 0), releases[1].version)
    }

    @Test
    fun `groups a release by level, biggest news first`() {
        val groups = ChangelogParser.parse(changelog)[0].groups
        assertEquals(listOf(Level.Major, Level.Minor, Level.Patch), groups.map { it.level })
        assertEquals(listOf("Drop the code login"), groups[0].entries)
        assertEquals(listOf("Show the changelog in the settings and after an update"), groups[1].entries)
    }

    @Test
    fun `strips the level off the entry it shows`() {
        val entries = ChangelogParser.parse(changelog).flatMap { it.groups }.flatMap { it.entries }
        assertTrue(entries.none { it.startsWith("minor:") || it.startsWith("patch:") })
    }

    @Test
    fun `keeps an entry that names no level, without inventing one for it`() {
        val releases = ChangelogParser.parse("## 1.0.0 — 2026-01-01\n- Something written by hand\n")
        assertEquals(listOf(null), releases[0].groups.map { it.level })
        assertEquals(listOf("Something written by hand"), releases[0].groups[0].entries)
    }

    @Test
    fun `survives a file it cannot make sense of`() {
        assertEquals(emptyList<Release>(), ChangelogParser.parse(""))
        assertEquals(emptyList<Release>(), ChangelogParser.parse("# Changelog\n\nNothing released yet.\n"))
        // A heading without a version, and entries with no release to belong to.
        assertEquals(emptyList<Release>(), ChangelogParser.parse("## Unreleased\n- minor: Dangling\n"))
    }

    @Test
    fun `reads the file the same way with Windows line endings`() {
        val windows = ChangelogParser.parse(changelog.replace("\n", "\r\n"))
        assertEquals(ChangelogParser.parse(changelog), windows)
    }

    @Test
    fun `takes a plain dash in the heading as well as an em dash`() {
        assertEquals(
            Version(2, 1, 0),
            ChangelogParser.parse("## 2.1.0 - 2026-05-05\n- patch: Fixed\n")[0].version,
        )
    }
}
