package me.lesh.chatter.channels

import me.lesh.chatter.emotes.ChannelEmoteSet
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelSettingsTest {
    private val chatting = StreamCategory("509658", "Just Chatting")
    private val art = StreamCategory("509660", "Art")
    private val stream = StreamDetails("Hello", chatting)

    @Test
    fun nothingTouchedIsNoChange() {
        assertEquals(ChannelSettingsChange(), settingsChange(stream, "Hello", chatting, "Just Chatting", null))
    }

    @Test
    fun aNewTitleIsSentTrimmedAndAnEmptyOneNot() {
        assertEquals("Bye", settingsChange(stream, " Bye ", chatting, "Just Chatting", null).title)
        assertEquals(null, settingsChange(stream, "  ", chatting, "Just Chatting", null).title)
    }

    @Test
    fun aPickedCategoryIsSent() {
        assertEquals(art, settingsChange(stream, "Hello", art, "Art", null).category)
    }

    @Test
    fun aHalfTypedCategoryChangesNothing() {
        assertEquals(null, settingsChange(stream, "Hello", chatting, "Ar", null).category)
    }

    @Test
    fun anEmptyCategoryFieldTakesTheCategoryAway() {
        assertEquals(StreamCategory.NONE, settingsChange(stream, "Hello", null, "", null).category)
        assertEquals(null, settingsChange(StreamDetails("Hello", null), "Hello", null, "", null).category)
    }

    @Test
    fun withoutTheStreamPartOnlyTheSetCanChange() {
        val set = ChannelEmoteSet("S", "Spooky", active = false)
        assertEquals(ChannelSettingsChange(emoteSet = set), settingsChange(null, "x", art, "Art", set))
    }

    @Test
    fun theActiveSetIsNoChange() {
        assertEquals(null, settingsChange(stream, "Hello", chatting, "Just Chatting", ChannelEmoteSet("A", "Main", active = true)).emoteSet)
    }

    @Test
    fun theCategoryStartingWithTheQueryComesBeforeTwitchsOrder() {
        val found = listOf("Just Cause", "Just Cause 2", "Just Chatting", "Just Chess").mapIndexed { i, name -> StreamCategory("$i", name) }
        assertEquals(listOf("Just Chatting", "Just Cause", "Just Cause 2", "Just Chess"), rankCategories("Just Chat", found).map { it.name })
    }

    @Test
    fun anExactNameComesFirstThenNamesThenWordsStartingWithIt() {
        val found = listOf("Artifact", "Pixel Art Games", "Art", "Smart Bomb").mapIndexed { i, name -> StreamCategory("$i", name) }
        assertEquals(listOf("Art", "Artifact", "Pixel Art Games", "Smart Bomb"), rankCategories(" art ", found).map { it.name })
    }

    @Test
    fun changedTagsAreSentWholeAndUntouchedOnesNot() {
        val tagged = StreamDetails("Hello", chatting, tags = listOf("German", "Chill"), language = "de")
        assertEquals(null, settingsChange(tagged, "Hello", chatting, "Just Chatting", null).tags)
        assertEquals(listOf("German"), settingsChange(tagged, "Hello", chatting, "Just Chatting", null, tags = listOf("German")).tags)
        assertEquals(emptyList<String>(), settingsChange(tagged, "Hello", chatting, "Just Chatting", null, tags = emptyList()).tags)
    }

    @Test
    fun aNewLanguageIsSentAndNoneIsNoChange() {
        val tagged = StreamDetails("Hello", chatting, language = "de")
        assertEquals("en", settingsChange(tagged, "Hello", chatting, "Just Chatting", null, language = "en").language)
        assertEquals(null, settingsChange(tagged, "Hello", chatting, "Just Chatting", null, language = "de").language)
        assertEquals(null, settingsChange(tagged, "Hello", chatting, "Just Chatting", null, language = "").language)
    }

    @Test
    fun tagsAreLettersAndDigitsOfAnyScriptUpToTwentyFive() {
        assertEquals(listOf("Deutsch"), StreamTags.add(emptyList(), " Deutsch "))
        assertEquals(listOf("日本語"), StreamTags.add(emptyList(), "日本語"))
        assertEquals(listOf("Vtuber2"), StreamTags.add(emptyList(), "Vtuber2"))
        assertEquals(null, StreamTags.add(emptyList(), "Two words"))
        assertEquals(null, StreamTags.add(emptyList(), "no-dash"))
        assertEquals(null, StreamTags.add(emptyList(), "a".repeat(26)))
        assertEquals(null, StreamTags.add(emptyList(), "  "))
    }

    @Test
    fun aTagAlreadyThereStaysOnceAndTheEleventhIsRefused() {
        assertEquals(listOf("Chill"), StreamTags.add(listOf("Chill"), "chill"))
        val full = (1..10).map { "Tag$it" }
        assertEquals(null, StreamTags.add(full, "More"))
    }
}
