package dev.chatter.app.chat

import dev.chatter.app.irc.IrcMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InboxWhisperTest {
    private fun whisper(line: String) = InboxWhisper.from(IrcMessage.parse(line)!!)

    @Test
    fun readsSenderAndText() {
        val w = whisper(
            "@badges=;color=#FF0000;display-name=Friend;message-id=7;thread-id=12-34;user-id=12 " +
                ":friend!friend@friend.tmi.twitch.tv WHISPER lukas :are you there?"
        )!!
        assertEquals("friend", w.login)
        assertEquals("Friend", w.displayName)
        assertEquals("are you there?", w.text)
        assertEquals(0xFFFF0000.toInt(), w.color)
        assertEquals("12-34-7", w.id)
    }

    @Test
    fun fallsBackToTheLoginWhenTagsAreMissing() {
        val w = whisper(":friend!friend@friend.tmi.twitch.tv WHISPER lukas :hi")!!
        assertEquals("friend", w.displayName)
        assertNull(w.color)
    }

    /** Twitch numbers whispers per thread, so two threads may both be at message 1. */
    @Test
    fun differentThreadsWithTheSameNumberStayApart() {
        val one = whisper("@message-id=1;thread-id=1-2 :a!a@a.tmi.twitch.tv WHISPER lukas :hi")!!
        val two = whisper("@message-id=1;thread-id=1-3 :b!b@b.tmi.twitch.tv WHISPER lukas :hi")!!
        assertNotEquals(one.id, two.id)
    }

    @Test
    fun ignoresALineWithoutText() {
        assertNull(whisper(":friend!friend@friend.tmi.twitch.tv WHISPER lukas"))
    }
}
