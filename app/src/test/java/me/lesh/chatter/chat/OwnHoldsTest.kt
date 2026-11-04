package me.lesh.chatter.chat

import me.lesh.chatter.eventsub.HoldStatus
import me.lesh.chatter.settings.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnHoldsTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val buffers = MessageBuffers(scope, dispatcher, MutableStateFlow(Settings()))
    private val clock = 1_000_000L
    private val holds = OwnHolds(buffers, object : OwnHoldNotices {
        override fun allowed() = "allowed"
        override fun denied() = "denied"
    }, now = { clock })

    private fun own(id: String, text: String, deleted: Boolean = true) = ChatItem(
        id = id, channel = "forsen", kind = MessageKind.Chat, timestamp = clock - 1_000,
        login = "me", text = text, isOwn = true, deleted = deleted,
    )

    private fun shown(body: (shown: () -> List<ChatItem>) -> Unit) = runTest(dispatcher) {
        buffers.open("forsen")
        val watcher = launch { buffers.messages("forsen").collect {} }
        body {
            advanceUntilIdle()
            buffers.messages("forsen").value
        }
        watcher.cancel()
    }

    @Test
    fun anAllowedMessageLosesItsStrokeAndTakesTwitchsId() = shown { shown ->
        // Struck out by IRC's refusal before EventSub said why.
        buffers.add(own("local-1", "hello there"))
        holds.onHeld("forsen", "twitch-1", "hello there")
        holds.onResolved("forsen", "twitch-1", HoldStatus.Approved)
        val (message, notice) = shown()
        assertEquals("twitch-1", message.id)
        assertEquals(false, message.deleted)
        assertEquals("allowed", notice.text)
    }

    @Test
    fun aRepeatMarkDoesNotKeepTheTextsApart() = shown { shown ->
        buffers.add(own("local-1", "same again \uDB40\uDC00"))
        holds.onHeld("forsen", "twitch-1", "same again")
        holds.onResolved("forsen", "twitch-1", HoldStatus.Approved)
        assertEquals(false, shown().first().deleted)
    }

    @Test
    fun aDeniedMessageStaysStruckOut() = shown { shown ->
        buffers.add(own("local-1", "rude"))
        holds.onHeld("forsen", "twitch-1", "rude")
        holds.onResolved("forsen", "twitch-1", HoldStatus.Denied)
        assertEquals(listOf(true, false), shown().map { it.deleted })
        assertEquals("denied", shown().last().text)
    }

    @Test
    fun anExpiredHoldSaysNothing() = shown { shown ->
        buffers.add(own("local-1", "rude"))
        holds.onHeld("forsen", "twitch-1", "rude")
        holds.onResolved("forsen", "twitch-1", HoldStatus.Expired)
        assertEquals(listOf("local-1"), shown().map { it.id })
    }
}
