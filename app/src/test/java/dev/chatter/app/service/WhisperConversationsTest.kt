package dev.chatter.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A whisper notification shows one account's private messages and replies as that account, so
 * neither may move to whichever account is active by the time a held-up whisper gets through.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WhisperConversationsTest {
    private var active: String? = "a"
    private val conversations = WhisperConversations { active }

    private fun line(text: String, own: Boolean = false) = WhisperConversations.Line(text, timestamp = 0, own = own)

    @Test
    fun theAnswerGoesOutAsTheAccountTheWhisperWasSentTo() {
        val thread = conversations.add("a", "Friend", "Friend", "7", line("hi"))!!
        assertEquals("a", thread.owner)
        assertEquals("7", thread.userId)
    }

    /** What [ChatNotifier.notifyWhisper] does: load the sender's picture, then show the line. */
    @Test
    fun aWhisperHeldUpWhileTheAccountChangesIsNotShown() = runTest {
        val picture = CompletableDeferred<Unit>()
        val shown = async {
            picture.await()
            conversations.add("a", "friend", "Friend", "7", line("a secret for a"))
        }
        runCurrent()

        active = "b"
        conversations.clear()
        picture.complete(Unit)

        assertNull(shown.await())
        assertEquals(emptySet<String>(), conversations.clear())
    }

    @Test
    fun nothingIsShownOnceLoggedOut() = runTest {
        val picture = CompletableDeferred<Unit>()
        val shown = async {
            picture.await()
            conversations.add("a", "friend", "Friend", "7", line("hi"))
        }
        runCurrent()
        active = null
        picture.complete(Unit)
        assertNull(shown.await())
    }

    /** What [ReplyReceiver] reports after sending, which can take longer than a switch. */
    @Test
    fun anAnswerReportedAfterTheSwitchStartsNoConversationForTheNewAccount() = runTest {
        conversations.add("a", "friend", "Friend", "7", line("hi"))
        val sent = CompletableDeferred<Unit>()
        val reported = async {
            sent.await()
            conversations.add("a", "friend", name = null, userId = null, line("my answer", own = true))
        }
        runCurrent()

        active = "b"
        conversations.clear()
        sent.complete(Unit)

        assertNull(reported.await())
        assertEquals(emptySet<String>(), conversations.clear())
    }

    @Test
    fun theSameSenderWritingToTheNextAccountStartsAfreshConversation() {
        conversations.add("a", "friend", "Friend", "7", line("for a"))
        active = "b"
        val thread = conversations.add("b", "friend", "Friend", "7", line("for b"))!!
        assertEquals("b", thread.owner)
        assertEquals(listOf("for b"), thread.lines.map { it.text })
    }

    @Test
    fun clearingNamesTheConversationsToRemove() {
        conversations.add("a", "Friend", "Friend", "7", line("hi"))
        conversations.add("a", "other", "Other", "8", line("ho"))
        assertEquals(setOf("friend", "other"), conversations.clear())
        assertEquals(emptySet<String>(), conversations.clear())
    }
}
