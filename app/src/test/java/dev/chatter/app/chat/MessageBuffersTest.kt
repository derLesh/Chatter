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
}
