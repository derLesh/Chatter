package dev.chatter.app.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.MessageKind
import dev.chatter.app.chat.ReplyInfo
import dev.chatter.app.chat.SendResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Drafts that Android saved with the window, and the account they belong to. */
class SavedDraftsTest {
    private val scope = TestScope()

    private val outbox = object : Outbox {
        override suspend fun send(channel: String, text: String, replyTo: ChatItem?) = SendResult.Ok
        override fun slowSeconds(channel: String) = 0
        override suspend fun report(result: SendResult) = Unit
    }

    private fun composer(scope: CoroutineScope = this.scope) = Composer(scope, outbox)

    private val question = ChatItem(
        id = "q", channel = "xqc", kind = MessageKind.Chat, timestamp = 7, login = "asker",
        displayName = "Asker", text = "who won?",
        reply = ReplyInfo("p", "first", "First", "earlier", threadId = "root"),
    )

    /** A composer written in as [account] the way the user would, then saved and brought back. */
    private fun recreated(account: String = "a", write: Composer.() -> Unit): Composer {
        val before = composer()
        before.useAccount(account, setOf(account))
        before.write()
        val saved = before.save()
        return composer().apply { saved?.let(::restore) }
    }

    @Test
    fun textSelectionReplyAndChannelComeBack() {
        val after = recreated {
            show("forsen", listOf("forsen"))
            input = TextFieldValue("see you", TextRange(3))
            show("#both", listOf("forsen", "xqc"))
            startReply(question)
            input = TextFieldValue("forsen did")
        }
        after.useAccount("a", setOf("a"))
        assertEquals(setOf("forsen", "#both"), after.draftPages)

        after.show("#both", listOf("forsen", "xqc"))
        assertEquals("forsen did", after.input.text)
        assertEquals("xqc", after.sendChannel)
        val reply = after.replyTo!!
        assertEquals(listOf("q", "xqc", "asker", "Asker", "who won?"), listOf(reply.id, reply.channel, reply.login, reply.displayName, reply.text))
        assertEquals("root", reply.reply?.threadId)

        after.show("forsen", listOf("forsen"))
        assertEquals(TextFieldValue("see you", TextRange(3)), after.input)
        assertNull(after.replyTo)
    }

    @Test
    fun theChosenChannelOfACombinedChatComesBack() {
        val after = recreated {
            show("#both", listOf("forsen", "xqc"))
            selectSendChannel("xqc")
            input = TextFieldValue("hi xqc")
        }
        after.useAccount("a", setOf("a"))
        after.show("#both", listOf("forsen", "xqc"))
        assertEquals("xqc", after.sendChannel)
        assertEquals("hi xqc", after.input.text)
    }

    @Test
    fun draftsWaitForTheirAccount() {
        val after = recreated(account = "a") {
            show("forsen", listOf("forsen"))
            input = TextFieldValue("as a")
        }
        after.show("forsen", listOf("forsen"))
        after.useAccount("b", setOf("a", "b"))
        assertEquals("", after.input.text)

        after.useAccount("a", setOf("a", "b"))
        assertEquals("as a", after.input.text)
    }

    @Test
    fun aRemovedAccountTakesItsDraftsAlong() {
        val after = recreated(account = "a") {
            show("forsen", listOf("forsen"))
            input = TextFieldValue("as a")
        }
        after.useAccount("b", setOf("b"))
        after.useAccount("a", setOf("a", "b"))
        after.show("forsen", listOf("forsen"))
        assertEquals("", after.input.text)
    }

    @Test
    fun switchingAccountsKeepsEachOnesDrafts() {
        val composer = composer()
        composer.useAccount("a", setOf("a", "b"))
        composer.show("forsen", listOf("forsen"))
        composer.input = TextFieldValue("as a")

        composer.useAccount("b", setOf("a", "b"))
        assertEquals("", composer.input.text)
        composer.input = TextFieldValue("as b")

        composer.useAccount("a", setOf("a", "b"))
        assertEquals("as a", composer.input.text)
        composer.useAccount("b", setOf("a", "b"))
        assertEquals("as b", composer.input.text)
    }

    @Test
    fun aGuestKeepsNothing() {
        val composer = composer()
        composer.useAccount(null, emptySet())
        composer.show("forsen", listOf("forsen"))
        assertNull(composer.save())
    }

    @Test
    fun somethingElseInTheSavedStateIsIgnored() {
        val composer = composer()
        composer.restore("not json")
        composer.useAccount("a", setOf("a"))
        assertEquals(emptySet<String>(), composer.draftPages)
    }
}
