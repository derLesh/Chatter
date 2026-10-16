package dev.chatter.app.ui

import androidx.compose.ui.text.input.TextFieldValue
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What is left of a page's input once a message sent from it went out. */
class DraftTest {
    private val question = ChatItem(id = "q", channel = "forsen", kind = MessageKind.Chat, timestamp = 1)
    private val other = ChatItem(id = "o", channel = "forsen", kind = MessageKind.Chat, timestamp = 2)

    private fun draft(text: String, replyTo: ChatItem? = null) = Draft(TextFieldValue(text), replyTo)

    @Test
    fun anUnchangedInputIsCleared() {
        val sent = draft("hello", question)
        assertTrue(sent.afterSending(sent).isEmpty)
    }

    @Test
    fun textWrittenWhileSendingStays() {
        val left = draft("hello again", question).afterSending(draft("hello", question))
        assertEquals("hello again", left.input.text)
        assertNull(left.replyTo)
    }

    @Test
    fun aReplyStartedWhileSendingStays() {
        val left = draft("hello", other).afterSending(draft("hello", question))
        assertEquals("", left.input.text)
        assertEquals(other, left.replyTo)
    }

    @Test
    fun anotherPagesDraftIsNotTouched() {
        // Page B's draft against what page A sent: nothing in common.
        val left = draft("for b").afterSending(draft("for a", question))
        assertEquals(draft("for b"), left)
    }
}
