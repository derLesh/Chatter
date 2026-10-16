package dev.chatter.app.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.MessageKind
import dev.chatter.app.chat.ReplyInfo
import dev.chatter.app.chat.SendResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the user writes as they move between pages, answer messages and send: the flows of the
 * chat screen, without a phone, a login or live messages.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerFlowTest {
    /** A few messages in two channels, and the pages that show them. */
    private object Fixture {
        const val FORSEN = "forsen"
        const val XQC = "xqc"
        const val BOTH = "#both"
        val bothChannels = listOf(FORSEN, XQC)

        val question = message("q1", XQC, "asker", "who is winning?")
        val joke = message("j1", FORSEN, "joker", "knock knock")
        /** An answer in a conversation that [question] started. */
        val answer = message("a1", XQC, "helper", "@asker nobody", ReplyInfo("q1", "asker", "Asker", "who is winning?"))

        private fun message(id: String, channel: String, login: String, text: String, reply: ReplyInfo? = null) = ChatItem(
            id = id, channel = channel, kind = MessageKind.Chat, timestamp = 0,
            login = login, displayName = login, text = text, reply = reply,
        )
    }

    /** Hands each message to the test, which decides when and how sending ends. */
    private class ControlledOutbox : Outbox {
        class Sending(val channel: String, val text: String, val replyTo: ChatItem?) {
            val result = CompletableDeferred<SendResult>()
        }

        val sending = ArrayDeque<Sending>()
        val reported = mutableListOf<SendResult>()

        override suspend fun send(channel: String, text: String, replyTo: ChatItem?): SendResult =
            Sending(channel, text, replyTo).also(sending::addLast).result.await()

        override fun slowSeconds(channel: String) = 0

        override suspend fun report(result: SendResult) {
            reported += result
        }
    }

    private val scope = TestScope()
    private val outbox = ControlledOutbox()
    private val composer = Composer(scope, outbox).apply { useAccount("me", setOf("me")) }

    private fun showChannel(channel: String) = composer.show(channel, listOf(channel))
    private fun showBoth() = composer.show(Fixture.BOTH, Fixture.bothChannels)

    private fun type(text: String) {
        composer.input = TextFieldValue(text, TextRange(text.length))
    }

    /** Sends and returns what reached the chat; the send has not finished yet. */
    private fun send(): ControlledOutbox.Sending {
        composer.send()
        scope.runCurrent()
        return outbox.sending.removeFirst()
    }

    private fun ControlledOutbox.Sending.finish(result: SendResult = SendResult.Ok) {
        this.result.complete(result)
        scope.runCurrent()
    }

    @Test
    fun eachPageKeepsItsOwnDraft() {
        showChannel(Fixture.FORSEN)
        type("for forsen")
        showBoth()
        type("for both")
        showChannel(Fixture.XQC)

        assertEquals("", composer.input.text)
        assertEquals(setOf(Fixture.FORSEN, Fixture.BOTH), composer.draftPages)
        showChannel(Fixture.FORSEN)
        assertEquals("for forsen", composer.input.text)
        showBoth()
        assertEquals("for both", composer.input.text)
    }

    @Test
    fun aCombinedChatKeepsTheChannelItWritesTo() {
        showBoth()
        assertEquals(Fixture.FORSEN, composer.sendChannel)
        composer.selectSendChannel(Fixture.XQC)
        type("hi xqc")
        showChannel(Fixture.FORSEN)
        assertEquals(Fixture.FORSEN, composer.sendChannel)

        showBoth()
        assertEquals(Fixture.XQC, composer.sendChannel)
        val sent = send()
        assertEquals(Fixture.XQC to "hi xqc", sent.channel to sent.text)
    }

    @Test
    fun aReplyGoesToTheChannelOfTheAnsweredMessage() {
        showBoth()
        composer.startReply(Fixture.question)
        assertEquals(Fixture.XQC, composer.sendChannel)
        type("forsen is")
        val sent = send()
        assertEquals(Fixture.XQC, sent.channel)
        assertEquals("q1", sent.replyTo?.id)
    }

    @Test
    fun choosingAnotherChannelDropsTheReply() {
        showBoth()
        composer.startReply(Fixture.question)
        type("still here")
        composer.selectSendChannel(Fixture.FORSEN)
        assertNull(composer.replyTo)
        assertEquals("still here", composer.input.text)
    }

    @Test
    fun aCanceledReplyKeepsTheText() {
        showChannel(Fixture.FORSEN)
        composer.startReply(Fixture.joke)
        type("who is there")
        composer.cancelReply()
        assertNull(composer.replyTo)
        assertEquals("who is there", composer.input.text)
        assertNull(send().replyTo)
    }

    @Test
    fun aReplyComesBackWithItsPage() {
        showBoth()
        composer.startReply(Fixture.question)
        type("forsen is")
        showChannel(Fixture.FORSEN)
        assertNull(composer.replyTo)

        showBoth()
        assertEquals("q1", composer.replyTo?.id)
        assertEquals(Fixture.XQC, composer.sendChannel)
        assertEquals(1, composer.replyStarts)
    }

    @Test
    fun aReplyLeavesWithTheChannelOfItsMessage() {
        showBoth()
        composer.startReply(Fixture.question)
        composer.show(Fixture.BOTH, listOf(Fixture.FORSEN))
        assertNull(composer.replyTo)
        assertEquals(Fixture.FORSEN, composer.sendChannel)
    }

    @Test
    fun aConversationAnswersInPlaceAndClosingItLeavesTheDraft() {
        // The conversation sheet starts a reply to the message it was opened on and closes with
        // cancelReply; its input is the page's input.
        showBoth()
        composer.selectSendChannel(Fixture.FORSEN)
        composer.startReply(Fixture.answer)
        type("thanks")
        send().finish()
        assertNull(composer.replyTo)

        composer.startReply(Fixture.answer)
        type("one more")
        composer.cancelReply()
        assertEquals("one more", composer.input.text)
        assertNull(composer.replyTo)
        assertEquals(Fixture.XQC, composer.sendChannel)
    }

    @Test
    fun aSendThatEndsOnAnotherPageLeavesThatPageAlone() {
        showChannel(Fixture.FORSEN)
        type("first")
        val sending = send()
        showChannel(Fixture.XQC)
        type("second, not sent")

        sending.finish()
        assertEquals("second, not sent", composer.input.text)
        assertEquals(emptySet<String>(), composer.draftPages)
        showChannel(Fixture.FORSEN)
        assertEquals("", composer.input.text)
    }

    @Test
    fun aSendThatEndsAfterTypingOnKeepsTheNewText() {
        showChannel(Fixture.FORSEN)
        type("first")
        val sending = send()
        type("first and more")
        sending.finish()
        assertEquals("first and more", composer.input.text)
    }

    @Test
    fun aFailedSendKeepsTheInputAndSaysWhy() {
        showChannel(Fixture.FORSEN)
        type("hello")
        send().finish(SendResult.NotConnected)
        assertEquals("hello", composer.input.text)
        assertEquals(listOf(SendResult.NotConnected), outbox.reported)
    }

    @Test
    fun aSecondTapWhileSendingSendsNothing() {
        showChannel(Fixture.FORSEN)
        type("once")
        val sending = send()
        composer.send()
        scope.runCurrent()
        assertEquals(0, outbox.sending.size)
        sending.finish()
    }

    @Test
    fun theDraftsComeBackAfterTheProcessEnds() {
        showBoth()
        composer.startReply(Fixture.question)
        type("forsen is")
        showChannel(Fixture.FORSEN)
        type("hello")
        val saved = composer.save()!!

        val after = Composer(scope, outbox)
        after.restore(saved)
        after.show(Fixture.FORSEN, listOf(Fixture.FORSEN))
        after.useAccount("me", setOf("me"))
        assertEquals("hello", after.input.text)
        after.show(Fixture.BOTH, Fixture.bothChannels)
        assertEquals("forsen is", after.input.text)
        assertEquals("q1", after.replyTo?.id)
        assertEquals(Fixture.XQC, after.sendChannel)
    }
}
