package dev.chatter.app.update

import dev.chatter.app.changelog.Level
import dev.chatter.app.changelog.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    /** Cut down from what api.github.com answered for v0.5.0, the fields the check reads and a few it does not. */
    private val latest = """
        {
          "url": "https://api.github.com/repos/derLesh/Chatter/releases/1",
          "html_url": "https://github.com/derLesh/Chatter/releases/tag/v0.6.0",
          "tag_name": "v0.6.0",
          "name": "Chatter 0.6.0",
          "draft": false,
          "prerelease": false,
          "published_at": "2026-10-02T18:04:11Z",
          "assets": [
            { "name": "chatter-0.6.0.apk.sha256", "browser_download_url": "https://github.com/derLesh/Chatter/releases/download/v0.6.0/chatter-0.6.0.apk.sha256" },
            { "name": "chatter-0.6.0.apk", "browser_download_url": "https://github.com/derLesh/Chatter/releases/download/v0.6.0/chatter-0.6.0.apk" }
          ],
          "body": "- minor: Show every channel as a tab\r\n- patch: Keep the tabs in view\r\n\r\n"
        }
    """.trimIndent()

    @Test
    fun `reads the version, the day and the apk of the latest release`() {
        val update = UpdateCheck.parse(latest)!!
        assertEquals("0.6.0", update.version)
        assertEquals("2026-10-02", update.date)
        // The checksum sits next to it and must not be what the button downloads.
        assertEquals("https://github.com/derLesh/Chatter/releases/download/v0.6.0/chatter-0.6.0.apk", update.url)
    }

    @Test
    fun `points at the release page when there is no apk`() {
        val update = UpdateCheck.parse(latest.replace(".apk\"", ".zip\""))!!
        assertEquals("https://github.com/derLesh/Chatter/releases/tag/v0.6.0", update.url)
    }

    @Test
    fun `turns the notes back into a release, the way the changelog shows one`() {
        val release = UpdateCheck.parse(latest)!!.release()!!
        assertEquals(Version(0, 6, 0), release.version)
        assertEquals("2026-10-02", release.date)
        assertEquals(listOf(Level.Minor, Level.Patch), release.groups.map { it.level })
        assertEquals(listOf("Show every channel as a tab"), release.groups[0].entries)
    }

    @Test
    fun `ignores what does not name a version`() {
        assertNull(UpdateCheck.parse(latest.replace("\"v0.6.0\"", "\"nightly\"")))
        assertNull(UpdateCheck.parse("""{"message": "API rate limit exceeded"}"""))
        assertNull(UpdateCheck.parse("not json"))
    }

    @Test
    fun `only a higher version is news`() {
        val update = UpdateCheck.parse(latest)!!
        assertTrue(UpdateCheck.isNewer(update, Version(0, 5, 0)))
        assertTrue(UpdateCheck.isNewer(update, Version(0, 5, 9)))
        assertFalse(UpdateCheck.isNewer(update, Version(0, 6, 0)))
        // A build ahead of the last release, which is what a development build usually is.
        assertFalse(UpdateCheck.isNewer(update, Version(0, 7, 0)))
        assertFalse(UpdateCheck.isNewer(update, null))
    }

    @Test
    fun `asks again once a day`() {
        val day = UpdateCheck.INTERVAL_MS
        assertTrue(UpdateCheck.isDue(null, 1_000))
        assertFalse(UpdateCheck.isDue(1_000, 1_000 + day - 1))
        assertTrue(UpdateCheck.isDue(1_000, 1_000 + day))
        // The clock was set back: waiting for it to catch up could take any amount of time.
        assertTrue(UpdateCheck.isDue(1_000 + day, 1_000))
    }
}
