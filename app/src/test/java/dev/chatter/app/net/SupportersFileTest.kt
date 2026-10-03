package dev.chatter.app.net

import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * The repository's supporter list, read like the app reads it. It is edited by hand and the app
 * silently tolerates mistakes (a missing or plain badge), so this is where they get caught.
 */
class SupportersFileTest {
    private val file = File("../docs/supporters.json")

    private fun String.isDate() = runCatching { LocalDate.parse(this) }.isSuccess

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
            assertTrue("an entry with nobody behind it: ${supporter.twitch}", supporter.github.isNotEmpty())
            // The app ignores unreadable dates and shows the plain badge, which would hide a typo.
            supporter.monthlySince?.let { assertTrue("not a date: $it", it.isDate()) }
            assertTrue("not a date: ${supporter.since}", supporter.since.isDate())
            assertTrue("a sponsorship cannot have happened less than never", supporter.oneTime >= 0)
        }
    }
}
