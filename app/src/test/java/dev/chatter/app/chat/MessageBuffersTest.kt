package dev.chatter.app.chat

import dev.chatter.app.settings.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The buffers on a test dispatcher: the same single thread they run on in the app, only one the
 * test moves along itself, so that the publishing can be watched happening.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageBuffersTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val settings = MutableStateFlow(Settings(messageLimit = 3))
    private val buffers = MessageBuffers(scope, dispatcher, settings)

    private fun message(channel: String, id: String, text: String = id, login: String = "someone") =
        ChatItem(
            id = id,
            channel = channel,
            kind = MessageKind.Chat,
            timestamp = id.hashCode().toLong(),
            login = login,
            text = text,
        )

    /**
     * Runs a test with somebody watching the given channels. Nothing is published to a channel
     * nobody is looking at, which is the point of the publishing but makes for a quiet test. The
     * collectors are stopped at the end, or the test would wait on them for ever.
     */
    private fun watching(vararg channels: String, body: TestScope.(shown: (String) -> List<ChatItem>) -> Unit) =
        runTest(dispatcher) {
            channels.forEach { buffers.open(it) }
            val watchers = channels.map { channel -> launch { buffers.messages(channel).collect {} } }
            advanceUntilIdle()
            body { channel -> buffers.messages(channel).value }
            watchers.forEach { it.cancel() }
        }

    @Test
    fun emotesThatArriveLateAreDrawnIntoTheMessagesAlreadyOnScreen() = watching("forsen") { shown ->
        // Stands in for the emote tables: empty until the provider that was down answers.
        var known = emptySet<String>()
        fun body() = MessageBody.lazily(
            segments = { listOf(if ("catJAM" in known) Segment.Text("<emote>") else Segment.Text("catJAM")) },
            badges = { emptyList() },
            worthKeeping = { known.isEmpty() },
        )
        buffers.add(message("forsen", "1").copy(body = body()))
        advanceUntilIdle()
        assertEquals(listOf(Segment.Text("catJAM")), shown("forsen").single().segments)

        known = setOf("catJAM")
        buffers.rebuildAll()
        advanceUntilIdle()
        assertEquals(listOf(Segment.Text("<emote>")), shown("forsen").single().segments)
    }

    @Test
    fun aMessageIsNotBuiltAgainOnceEveryProviderHasAnswered() = watching("forsen") { shown ->
        var builds = 0
        val body = MessageBody.lazily(
            segments = { builds++; emptyList() },
            badges = { emptyList() },
            worthKeeping = { false },
        )
        buffers.add(message("forsen", "1").copy(body = body))
        advanceUntilIdle()
        buffers.rebuildAll()
        advanceUntilIdle()
        assertEquals(1, builds)
        assertTrue(shown("forsen").single().segments.isEmpty())
    }

    @Test
    fun aMessageCanBeTakenBackOutByItsId() = watching("forsen") { shown ->
        buffers.add(message("forsen", "1"))
        buffers.add(message("forsen", "2"))
        advanceUntilIdle()
        buffers.remove("forsen", "1")
        advanceUntilIdle()
        assertEquals(listOf("2"), shown("forsen").map { it.id })

        buffers.add(message("forsen", "1"))
        advanceUntilIdle()
        assertEquals("the id is free again, so the same line can be shown once more",
            listOf("2", "1"), shown("forsen").map { it.id })
    }

    @Test
    fun aMessageReachesTheScreen() = watching("forsen") { shown ->
        buffers.add(message("forsen", "1"))
        advanceUntilIdle()
        assertEquals(listOf("1"), shown("forsen").map { it.id })
    }

    @Test
    fun theSameMessageTwiceIsKeptOnce() = watching("forsen") { shown ->
        buffers.add(message("forsen", "1"))
        buffers.add(message("forsen", "1"))
        advanceUntilIdle()
        assertEquals(1, shown("forsen").size)
    }

    @Test
    fun aMessageForAChannelThatIsNotJoinedGoesNowhere() = watching("forsen") { shown ->
        buffers.add(message("xqc", "1"))
        advanceUntilIdle()
        assertTrue(shown("forsen").isEmpty())
        assertTrue(shown("xqc").isEmpty())
    }

    @Test
    fun theOldestGoWhenTheLimitIsReached() = watching("forsen") { shown ->
        listOf("1", "2", "3", "4").forEach { buffers.add(message("forsen", it)) }
        advanceUntilIdle()
        assertEquals(listOf("2", "3", "4"), shown("forsen").map { it.id })
    }

    @Test
    fun aMessageThatWasTrimmedAwayCanArriveAgain() = watching("forsen") { shown ->
        listOf("1", "2", "3", "4").forEach { buffers.add(message("forsen", it)) }
        buffers.add(message("forsen", "1"))
        advanceUntilIdle()
        // The id left with the message, so it is a new one again — and pushes the limit along.
        assertEquals(listOf("3", "4", "1"), shown("forsen").map { it.id })
    }

    @Test
    fun historyIsFoldedInByTheTimeItWasWritten() = watching("forsen") { shown ->
        buffers.add(message("forsen", "live").copy(timestamp = 300))
        buffers.merge(
            "forsen",
            listOf(
                message("forsen", "old").copy(timestamp = 100),
                message("forsen", "older").copy(timestamp = 200),
                message("forsen", "live").copy(timestamp = 300),
            ),
        )
        advanceUntilIdle()
        assertEquals(listOf("old", "older", "live"), shown("forsen").map { it.id })
    }

    @Test
    fun theBackgroundsKeepAlternatingAcrossAMerge() = watching("forsen") { shown ->
        settings.value = Settings(messageLimit = 10)
        buffers.add(message("forsen", "live").copy(timestamp = 300))
        buffers.merge("forsen", listOf(message("forsen", "old").copy(timestamp = 100)))
        advanceUntilIdle()
        assertEquals(listOf(false, true), shown("forsen").map { it.alternate })
    }

    @Test
    fun deletedMessagesAreStruckThroughOrHiddenAsTheSettingSays() = watching("forsen") { shown ->
        buffers.add(message("forsen", "1", login = "rulebreaker"))
        buffers.add(message("forsen", "2"))
        buffers.markDeleted("forsen") { it.login == "rulebreaker" }
        advanceUntilIdle()
        assertEquals(listOf(true, false), shown("forsen").map { it.deleted })

        settings.value = Settings(messageLimit = 3, showDeleted = false)
        buffers.republishAll()
        advanceUntilIdle()
        assertEquals(listOf("2"), shown("forsen").map { it.id })
    }

    @Test
    fun mutingAWordTakesWhatIsAlreadyThereWithIt() = watching("forsen") { shown ->
        buffers.add(message("forsen", "1", text = "spoiler ahead"))
        buffers.add(message("forsen", "2", text = "nothing to see"))
        buffers.dropMuted(MuteFilter(keywords = listOf("spoiler")))
        advanceUntilIdle()
        assertEquals(listOf("2"), shown("forsen").map { it.id })
    }

    @Test
    fun unreadIsCountedPerChannelAndClearedWhenItIsRead() = watching("forsen") { _ ->
        buffers.countUnread("forsen")
        buffers.countUnread("forsen")
        buffers.countUnread("xqc")
        buffers.add(message("forsen", "1")) // the counts ride along with a publish
        advanceUntilIdle()
        assertEquals(mapOf("forsen" to 2, "xqc" to 1), buffers.unreadMessages.value)

        buffers.clearUnread("forsen")
        advanceUntilIdle()
        assertEquals(mapOf("xqc" to 1), buffers.unreadMessages.value)
    }

    @Test
    fun nothingIsPublishedWhileNobodyIsWatching() = runTest(dispatcher) {
        buffers.open("forsen")
        val flow = buffers.messages("forsen")
        buffers.add(message("forsen", "1"))
        advanceUntilIdle()
        assertTrue("no subscriber, no work", flow.value.isEmpty())

        // Watching it catches up on everything that arrived in the meantime.
        val watcher = launch { flow.collect {} }
        advanceUntilIdle()
        assertEquals(listOf("1"), flow.value.map { it.id })
        watcher.cancel()
    }

    @Test
    fun aChannelThatIsLeftTakesItsMessagesAndItsCountWithIt() = watching("forsen") { _ ->
        buffers.add(message("forsen", "1"))
        buffers.countUnread("forsen")
        advanceUntilIdle()

        buffers.close("forsen")
        advanceUntilIdle()
        assertEquals(emptyMap<String, Int>(), buffers.unreadMessages.value)
        assertEquals(emptyList<String>(), buffers.channels())

        // Joining it again starts from nothing rather than from what was there before.
        buffers.open("forsen")
        val second = launch { buffers.messages("forsen").collect {} }
        advanceUntilIdle()
        assertTrue(buffers.messages("forsen").value.isEmpty())
        second.cancel()
    }

    @Test
    fun onlyTheOneUsersMessagesComeBackForTheirCard() = watching("forsen") { _ ->
        buffers.add(message("forsen", "1", login = "lukas"))
        buffers.add(message("forsen", "2", login = "someone"))
        buffers.add(message("forsen", "3", login = "LUKAS"))
        advanceUntilIdle()
        val theirs = async { buffers.from("forsen", "lukas", limit = 30) }
        advanceUntilIdle()
        assertEquals(listOf("1", "3"), theirs.getCompleted().map { it.id })
    }

    /**
     * A combined chat of forsen and xqc, watched on its own: the channels themselves are joined
     * but not on screen, which is how the app has it while the combined chat is the page shown.
     */
    private fun watchingCombined(body: TestScope.(shown: () -> List<ChatItem>) -> Unit) = runTest(dispatcher) {
        buffers.open("forsen")
        buffers.open("xqc")
        buffers.setGroups(mapOf("+both" to listOf("forsen", "xqc")))
        val watcher = launch { buffers.messages("+both").collect {} }
        advanceUntilIdle()
        body { buffers.messages("+both").value }
        watcher.cancel()
    }

    private fun at(channel: String, id: String, timestamp: Long) = message(channel, id).copy(timestamp = timestamp)

    @Test
    fun aCombinedChatShowsItsChannelsInTheOrderTheyWereWritten() = watchingCombined { shown ->
        buffers.add(at("forsen", "f1", 100))
        buffers.add(at("xqc", "x1", 150))
        buffers.add(at("forsen", "f2", 200))
        advanceUntilIdle()
        assertEquals(listOf("f1", "x1", "f2"), shown().map { it.id })
        assertEquals(listOf("forsen", "xqc", "forsen"), shown().map { it.channel })
    }

    @Test
    fun aChannelsOwnOrderIsKeptInACombinedChat() = watchingCombined { shown ->
        // The user's own line goes in with the phone's clock, which can be ahead of Twitch's.
        buffers.add(at("forsen", "own", 300))
        buffers.add(at("forsen", "reply", 250))
        buffers.add(at("xqc", "x1", 280))
        advanceUntilIdle()
        assertEquals("forsen's lines stay in the order they were put in",
            listOf("x1", "own", "reply"), shown().map { it.id })
    }

    @Test
    fun theRowsOfACombinedChatAlternateOnTheirOwn() = watchingCombined { shown ->
        // Both are the first of their channel, so both channels shade them the same.
        buffers.add(at("forsen", "f1", 100))
        buffers.add(at("xqc", "x1", 200))
        advanceUntilIdle()
        assertEquals(listOf(false, true), shown().map { it.alternate })
    }

    @Test
    fun aRowOfACombinedChatKeepsItsShadeWhenTheOldestGo() = watchingCombined { shown ->
        buffers.add(at("forsen", "f1", 100))
        buffers.add(at("xqc", "x1", 200))
        buffers.add(at("forsen", "f2", 300))
        advanceUntilIdle()
        val before = shown().associate { it.id to it.alternate }

        buffers.add(at("xqc", "x2", 400))
        advanceUntilIdle()
        assertEquals("the limit of three pushed f1 out", listOf("x1", "f2", "x2"), shown().map { it.id })
        assertEquals(before["x1"], shown()[0].alternate)
        assertEquals(before["f2"], shown()[1].alternate)
        assertEquals(!shown()[1].alternate, shown()[2].alternate)
    }

    @Test
    fun aCombinedChatIsHeldToTheLimitOfOneChannel() = watchingCombined { shown ->
        listOf(1L, 2L, 3L).forEach { buffers.add(at("forsen", "f$it", it * 10)) }
        listOf(1L, 2L, 3L).forEach { buffers.add(at("xqc", "x$it", it * 10 + 5)) }
        advanceUntilIdle()
        assertEquals(listOf("x2", "f3", "x3"), shown().map { it.id })
    }

    @Test
    fun theSameMessageInTwoChannelsIsOneRow() = watchingCombined { shown ->
        buffers.add(at("forsen", "shared", 100))
        buffers.add(at("xqc", "shared", 100))
        advanceUntilIdle()
        assertEquals(listOf("shared"), shown().map { it.id })
    }

    @Test
    fun aCombinedChatFollowsWhenItsChannelsChange() = watchingCombined { shown ->
        buffers.open("moondye7")
        buffers.add(at("xqc", "x1", 100))
        buffers.add(at("moondye7", "m1", 200))
        advanceUntilIdle()
        assertEquals(listOf("x1"), shown().map { it.id })

        buffers.setGroups(mapOf("+both" to listOf("forsen", "moondye7")))
        advanceUntilIdle()
        assertEquals(listOf("m1"), shown().map { it.id })
    }

    @Test
    fun deletedMessagesLeaveACombinedChatAsTheSettingSays() = watchingCombined { shown ->
        settings.value = Settings(messageLimit = 3, showDeleted = false)
        buffers.add(at("forsen", "f1", 100))
        buffers.add(at("xqc", "x1", 200))
        buffers.markDeleted("forsen") { true }
        advanceUntilIdle()
        assertEquals(listOf("x1"), shown().map { it.id })
    }
}
