package dev.chatter.app.net

import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The supporter list in this repository, read the way the app reads it.
 *
 * It is edited by hand and served straight from GitHub Pages, so nothing else would catch a comma
 * in the wrong place or a kind spelled almost right — the app is built to shrug both off, and the
 * badge would simply be missing or plain for somebody who paid for it.
 */
class SupportersFileTest {
    private val file = File("../docs/supporters.json")

    @Test
    fun theListIsOneTheAppCanRead() {
        assertTrue("not where it is expected: ${file.absolutePath}", file.exists())
        val list = AppJson.decodeFromJsonElement<ChatterSupporters>(AppJson.parseToJsonElement(file.readText()))

        assertTrue("somebody should be in it", list.supporters.isNotEmpty())
        list.supporters.forEach { supporter ->
            assertTrue("an entry without a Twitch id can never be matched to anybody", supporter.twitch.isNotEmpty())
            assertTrue(
                "a Twitch user id is a number, this is not: ${supporter.twitch}",
                supporter.twitch.all(Char::isDigit),
            )
            // The app wears the plain badge for a kind it does not know, which is what makes a
            // typo here invisible. Here is where it is not.
            assertTrue(
                "unknown kind: ${supporter.kind}",
                supporter.kind in setOf(ChatterSupporter.KIND_ONCE, ChatterSupporter.KIND_MONTHLY),
            )
        }
    }
}
