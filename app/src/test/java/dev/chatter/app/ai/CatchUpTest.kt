package dev.chatter.app.ai

import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.MessageKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Gemini Nano is given of a chat, and how its answer is cleaned up. */
class CatchUpTest {
    private var next = 0

    private fun chat(name: String, text: String, kind: MessageKind = MessageKind.Chat, channel: String = "forsen") =
        ChatItem(id = "${next++}", channel = channel, kind = kind, timestamp = next.toLong(), displayName = name, text = text)

    @Test
    fun chatLinesNameTheirWriter() {
        val missed = listOf(chat("Alice", "hi"), chat("Bob", "waves", MessageKind.Action))
        assertEquals(listOf("Alice: hi", "* Bob waves"), CatchUp.lines(missed))
    }

    @Test
    fun aSubShowsTwitchsLineAndWhatWasWrittenWithIt() {
        val sub = chat("Alice", "love the stream", MessageKind.UserNotice).copy(systemText = "Alice subscribed for 3 months.")
        val raid = chat("Bob", "", MessageKind.UserNotice).copy(systemText = "50 raiders from Bob have joined!")
        assertEquals(
            listOf("[Alice subscribed for 3 months.] Alice: love the stream", "[50 raiders from Bob have joined!]"),
            CatchUp.lines(listOf(sub, raid)),
        )
    }

    @Test
    fun aNoticeIsWrittenOnceAndDeletedMessagesAreLeftOut() {
        val notice = ChatItem(id = "n", channel = "forsen", kind = MessageKind.Notice, timestamp = 0, systemText = "Slow mode", text = "Slow mode")
        val deleted = chat("Eve", "spam").copy(deleted = true)
        assertEquals(listOf("[Slow mode]"), CatchUp.lines(listOf(notice, deleted)))
    }

    @Test
    fun aCombinedChatMarksEachLineWithItsChannel() {
        assertEquals(listOf("#xqc Alice: hi"), CatchUp.lines(listOf(chat("Alice", "hi", channel = "xqc")), withChannel = true))
    }

    @Test
    fun aLongMessageIsCut() {
        val line = CatchUp.lines(listOf(chat("Alice", "a".repeat(500)))).single()
        assertEquals(CatchUp.MAX_LINE, line.length)
        assertTrue(line.endsWith("…"))
    }

    @Test
    fun aChatThatFitsIsKeptWhole() {
        assertEquals("Alice: hi\nBob: yo", CatchUp.fit(listOf("Alice: hi", "Bob: yo")))
    }

    @Test
    fun tooMuchChatIsThinnedOutEvenlyAndStaysInBudget() {
        val lines = CatchUp.lines((0 until 1000).map { chat("u$it", "message number $it") })
        val kept = CatchUp.fit(lines, budget = 2_000).lines()
        val numbers = kept.map { it.substringBefore(':').removePrefix("u").toInt() }
        assertTrue(kept.sumOf { it.length + 1 } - 1 <= 2_000)
        // Taken from the whole time away, not only its end or its start, and still in order.
        assertEquals(0, numbers.first())
        assertTrue(numbers.last() > 900)
        assertEquals(numbers.sorted(), numbers)
    }

    @Test
    fun thePromptAsksForTheLanguageAndNamesTheUser() {
        val prompt = CatchUp.prompt("forsen", "German", "bob")
        assertTrue("short bullet points, in German" in prompt)
        assertTrue("\"bob\"" in prompt)
        assertTrue(prompt.endsWith("Chat:\n"))
        assertFalse("\"bob\"" in CatchUp.prompt("forsen", "German", null))
    }

    /** A safety check that objects to any stretch holding one of [bad]; counts how often it was asked. */
    private class Filter(private val bad: Set<Int>) {
        var asked = 0
        val passes: suspend (Int, Int) -> Boolean = { from, to -> asked++; (from until to).none { it in bad } }
    }

    @Test
    fun oneRefusedMessageIsFoundAmongMany() = runBlocking {
        val refused = CatchUp.refused(100, Filter(setOf(57)).passes)
        assertTrue(57 in refused)
        assertTrue(refused.size <= CatchUp.NARROW)
    }

    @Test
    fun refusedMessagesFarApartAreEachFoundAndTheRestKept() = runBlocking {
        val refused = CatchUp.refused(100, Filter(setOf(3, 88)).passes)
        assertTrue(3 in refused && 88 in refused)
        assertTrue(refused.size <= 2 * CatchUp.NARROW)
    }

    @Test
    fun aChatRefusedThroughoutStopsAskingAndCountsAsRefused() = runBlocking {
        val filter = Filter((0 until 100).toSet())
        val refused = CatchUp.refused(100, filter.passes)
        assertEquals((0 until 100).toSet(), refused)
        assertEquals(CatchUp.MAX_TRIES, filter.asked)
    }

    @Test
    fun aChatRefusedOnlyAsAWholeLeavesNothingOut() = runBlocking {
        assertEquals(emptySet<Int>(), CatchUp.refused(100) { _, _ -> true })
    }

    @Test
    fun markdownBulletsBecomePlainOnes() {
        assertEquals("• Raid came in\n• chat spams KEKW", CatchUp.tidy("* **Raid** came in\n  - chat spams KEKW\n"))
    }
}
