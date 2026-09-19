package dev.chatter.app.util

import dev.chatter.app.channels.ChannelRepository
import dev.chatter.app.emotes.Emote
import dev.chatter.app.emotes.EmoteProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutocompleteTest {
    private fun e(name: String) = Emote(name, name, "", EmoteProvider.SevenTv)

    @Test
    fun currentWordAtCursor() {
        assertEquals("LU", Autocomplete.currentWord("hello LU", 8)?.text)
        assertEquals("hello", Autocomplete.currentWord("hello LU", 3)?.text)
        assertNull(Autocomplete.currentWord("hello ", 6))
    }

    @Test
    fun emoteRanking() {
        val list = listOf(e("OMEGALUL"), e("LULW"), e("LUL"), e("lulWut"), e("KEKW"))
        assertEquals(listOf("LUL", "LULW", "lulWut", "OMEGALUL"), Autocomplete.rankEmotes("LUL", list).map { it.name })
        assertTrue(Autocomplete.rankEmotes("", list).isEmpty())
    }

    @Test
    fun userRankingKeepsRecency() {
        val users = listOf("zed", "Alice", "bob", "alfred")
        assertEquals(listOf("Alice", "alfred"), Autocomplete.rankUsers("@al", users))
        assertEquals(users, Autocomplete.rankUsers("@", users))
        // Names are also completed without an "@", so the same query works bare.
        assertEquals(listOf("Alice", "alfred"), Autocomplete.rankUsers("al", users))
    }

    @Test
    fun replaceWord() {
        val word = Autocomplete.currentWord("hi LU", 5)!!
        assertEquals("hi LULW " to 8, Autocomplete.replace("hi LU", word, "LULW"))

        val middle = Autocomplete.currentWord("hi LU there", 5)!!
        assertEquals("hi LULW there" to 8, Autocomplete.replace("hi LU there", middle, "LULW"))
    }

    @Test
    fun insertAddsSpaces() {
        assertEquals("hi Kappa " to 9, Autocomplete.insert("hi", 2, "Kappa"))
        assertEquals("Kappa " to 6, Autocomplete.insert("", 0, "Kappa"))
    }
}

class RateLimiterTest {
    @Test
    fun slidingWindow() {
        val limiter = RateLimiter(30_000)
        repeat(20) { assertTrue(limiter.tryAcquire(1_000L + it, 20)) }
        assertFalse(limiter.tryAcquire(2_000, 20))
        assertTrue("oldest expired", limiter.tryAcquire(31_000, 20))
        assertFalse(limiter.tryAcquire(31_000, 20))
    }
}

class ChannelNameTest {
    @Test
    fun normalize() {
        assertEquals("forsen", ChannelRepository.normalize(" #Forsen "))
        assertEquals("xqc", ChannelRepository.normalize("https://www.twitch.tv/xQc?sr=a"))
        assertEquals("a_b1", ChannelRepository.normalize("@a_b1"))
        assertNull(ChannelRepository.normalize("not valid!"))
        assertNull(ChannelRepository.normalize(""))
    }
}
