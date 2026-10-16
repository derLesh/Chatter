package dev.chatter.app.ui.chat

import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.MessageKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** "Keep the screen on" holds the screen only while the chat moves. */
@OptIn(ExperimentalCoroutinesApi::class)
class KeepScreenOnTest {
    private val messages = MutableStateFlow(listOf(message("1")))
    private val touches = MutableSharedFlow<Unit>(extraBufferCapacity = 10)

    private fun message(id: String) =
        ChatItem(id = id, channel = "channel", kind = MessageKind.Chat, timestamp = 0, text = id)

    @Test
    fun onWhenOpenedAndOffAfterTenQuietMinutes() = runTest {
        val screenOn = mutableListOf<Boolean>()
        backgroundScope.launch { screenOnWhileChatMoves(messages, touches).collect { screenOn += it } }
        runCurrent()
        assertEquals(listOf(true), screenOn)

        advanceTimeBy(SCREEN_ON_AFTER_ACTIVITY_MS - 1)
        runCurrent()
        assertEquals(listOf(true), screenOn)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(true, false), screenOn)
    }

    @Test
    fun aNewMessageStartsTheTenMinutesAgain() = runTest {
        val screenOn = mutableListOf<Boolean>()
        backgroundScope.launch { screenOnWhileChatMoves(messages, touches).collect { screenOn += it } }
        runCurrent()

        advanceTimeBy(SCREEN_ON_AFTER_ACTIVITY_MS - 1_000)
        messages.value = messages.value + message("2")
        runCurrent()
        advanceTimeBy(SCREEN_ON_AFTER_ACTIVITY_MS - 1_000)
        runCurrent()
        assertEquals("still on, the message came later", listOf(true), screenOn)

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf(true, false), screenOn)
    }

    @Test
    fun aMessageOrATouchTurnsItBackOn() = runTest {
        val screenOn = mutableListOf<Boolean>()
        backgroundScope.launch { screenOnWhileChatMoves(messages, touches).collect { screenOn += it } }
        advanceTimeBy(SCREEN_ON_AFTER_ACTIVITY_MS + 1)
        runCurrent()

        messages.value = messages.value + message("2")
        runCurrent()
        assertEquals(listOf(true, false, true), screenOn)

        advanceTimeBy(SCREEN_ON_AFTER_ACTIVITY_MS + 1)
        touches.emit(Unit)
        runCurrent()
        assertEquals(listOf(true, false, true, false, true), screenOn)
    }

    @Test
    fun aMessageChangedInPlaceIsNoNewOne() = runTest {
        val screenOn = mutableListOf<Boolean>()
        backgroundScope.launch { screenOnWhileChatMoves(messages, touches).collect { screenOn += it } }
        advanceTimeBy(SCREEN_ON_AFTER_ACTIVITY_MS + 1)
        runCurrent()

        // As when a mod deletes the newest message.
        messages.value = messages.value.map { it.copy(deleted = true) }
        runCurrent()
        assertEquals(listOf(true, false), screenOn)
    }
}
