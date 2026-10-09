package dev.chatter.app.emotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmojiCatalogTest {
    private val sample = """
        # a comment
        😄	smileys	smile
        😃	smileys	smiley
        😸	animals	smile_cat
        👍	people	+1 thumbsup
        🏳️‍🌈	flags	rainbow_flag
        ❓	nowhere	question
        broken line
    """.trimIndent()

    @Test
    fun readsEmojiGroupsAndShortcodes() {
        val emoji = EmojiCatalog.parse(sample)
        assertEquals(listOf("😄", "😃", "😸", "👍", "🏳️‍🌈"), emoji.map { it.value })
        assertEquals(EmojiGroup.People, emoji[3].group)
        assertEquals(listOf("+1", "thumbsup"), emoji[3].shortcodes)
    }

    @Test
    fun ignoresWindowsLineEndings() {
        assertEquals(listOf("smile"), EmojiCatalog.parse("😄\tsmileys\tsmile\r\n").single().shortcodes)
    }

    @Test
    fun prefixMatchesComeFirstShortestFirst() {
        val emoji = EmojiCatalog.parse(sample)
        val found = EmojiCatalog.search("smi", emoji).map { it.second }
        assertEquals(listOf("smile", "smiley", "smile_cat"), found)
        assertEquals(listOf("smile_cat"), EmojiCatalog.search("cat", emoji).map { it.second })
        assertEquals("👍", EmojiCatalog.search("THUMBS", emoji).single().first.value)
    }

    @Test
    fun takesShortcodesAfterAColon() {
        assertEquals("smi", EmojiCatalog.typedShortcode(":smi"))
        assertEquals("smile", EmojiCatalog.typedShortcode(":smile:"))
        assertEquals("+1", EmojiCatalog.typedShortcode(":+1"))
        assertNull(EmojiCatalog.typedShortcode(":s"))
        assertNull(EmojiCatalog.typedShortcode("smile"))
        assertNull(EmojiCatalog.typedShortcode(":)"))
        assertNull(EmojiCatalog.typedShortcode("::"))
    }

    @Test
    fun theShippedListReadsInFull() {
        val file = File("src/main/assets/emoji.tsv")
        val lines = file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val emoji = EmojiCatalog.parse(file.readText())
        assertEquals(lines.size, emoji.size)
        assertTrue(EmojiGroup.entries.all { group -> emoji.any { it.group == group } })
        assertEquals("😄", EmojiCatalog.search("smile", emoji).first().first.value)
    }
}
