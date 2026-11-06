package me.lesh.chatter.chat

import me.lesh.chatter.testing.MemoryStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Received and sent whispers become one conversation per person, like a chat app shows them. */
@OptIn(ExperimentalCoroutinesApi::class)
class WhisperConversationsTest {
    private fun received(id: String, login: String, name: String = login, read: Boolean = false, color: Int? = null) =
        InboxWhisper(id = id, login = login, userId = "id-$login", displayName = name, text = "hi", timestamp = 0, color = color, read = read)

    private fun sent(id: String, login: String) =
        InboxWhisper(id = id, login = login, displayName = login, text = "hey", timestamp = 0, read = true, outgoing = true)

    @Test
    fun groupsByPersonWithTheMostRecentFirst() {
        val conversations = conversationsOf(listOf(received("3", "mila"), sent("2", "bob"), received("1", "mila")))
        assertEquals(listOf("mila", "bob"), conversations.map { it.login })
        assertEquals("3", conversations[0].latest.id)
    }

    @Test
    fun takesTheNameAndColorFromTheirOwnWhispers() {
        val conversation = conversationsOf(listOf(sent("2", "mila"), received("1", "Mila", name = "Mila", color = 7))).single()
        assertEquals("mila", conversation.login)
        assertEquals("Mila", conversation.displayName)
        assertEquals(7, conversation.color)
        assertEquals("id-Mila", conversation.userId)
    }

    @Test
    fun countsOnlyTheirUnreadWhispers() {
        val conversation = conversationsOf(listOf(sent("3", "mila"), received("2", "mila"), received("1", "mila", read = true))).single()
        assertEquals(1, conversation.unread)
    }

    @Test
    fun aSentWhisperIsKeptForTheAccountThatSentIt() = runTest {
        val inbox = WhisperInboxRepository(MemoryStore(), MutableStateFlow("me"), MutableStateFlow(listOf("me")), backgroundScope)
        inbox.add(received("1", "mila", name = "Mila").copy(owner = "me"))
        inbox.addSent(owner = "me", login = "Mila", userId = "id-mila", displayName = null, text = "on my way", timestamp = 5)
        runCurrent()

        val reply = inbox.whispers.value.first()
        assertTrue(reply.outgoing)
        assertTrue(reply.read)
        assertEquals("Mila", reply.displayName)
        assertEquals(listOf("on my way", "hi"), whispersWith(inbox.whispers.value, "mila").map { it.text })
    }

    @Test
    fun aSentWhisperForAnAccountNoLongerOnThePhoneIsDropped() = runTest {
        val inbox = WhisperInboxRepository(MemoryStore(), MutableStateFlow("me"), MutableStateFlow(listOf("me")), backgroundScope)
        inbox.addSent(owner = "gone", login = "mila", userId = null, displayName = "Mila", text = "hi", timestamp = 5)
        runCurrent()
        assertEquals(emptyList<InboxWhisper>(), inbox.whispers.value)
    }

    @Test
    fun readingAndDeletingAConversationLeavesTheOthers() = runTest {
        val inbox = WhisperInboxRepository(MemoryStore(), MutableStateFlow("me"), MutableStateFlow(listOf("me")), backgroundScope)
        inbox.add(received("2", "bob").copy(owner = "me"))
        inbox.add(received("1", "mila").copy(owner = "me"))

        inbox.markConversationRead("MILA")
        runCurrent()
        assertEquals(mapOf("mila" to 0, "bob" to 1), inbox.conversations.value.associate { it.login to it.unread })

        inbox.clearConversation("bob")
        runCurrent()
        assertEquals(listOf("mila"), inbox.conversations.value.map { it.login })
    }
}
