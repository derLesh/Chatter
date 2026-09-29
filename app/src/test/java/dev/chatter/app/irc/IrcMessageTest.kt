package dev.chatter.app.irc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IrcMessageTest {
    @Test
    fun parsesPrivmsgWithTags() {
        val msg = IrcMessage.parse(
            "@badges=moderator/1,subscriber/12;color=#FF0000;display-name=Lukas;emotes=25:0-4;id=abc " +
                ":lukas!lukas@lukas.tmi.twitch.tv PRIVMSG #forsen :Kappa hello there"
        )!!
        assertEquals("PRIVMSG", msg.command)
        assertEquals("forsen", msg.channel)
        assertEquals("lukas", msg.nick)
        assertEquals("Kappa hello there", msg.trailing)
        assertEquals("moderator/1,subscriber/12", msg.tag("badges"))
        assertEquals("Lukas", msg.tag("display-name"))
    }

    @Test
    fun unescapesTagValues() {
        val msg = IrcMessage.parse("@system-msg=5\\sraiders\\sfrom\\:\\sx\\\\y;empty= :tmi.twitch.tv USERNOTICE #a")!!
        assertEquals("5 raiders from; x\\y", msg.tag("system-msg"))
        assertNull(msg.tag("empty"))
        assertEquals("", msg.tags["empty"])
    }

    @Test
    fun parsesMessagesWithoutTagsOrPrefix() {
        val ping = IrcMessage.parse("PING :tmi.twitch.tv")!!
        assertEquals("PING", ping.command)
        assertEquals(listOf("tmi.twitch.tv"), ping.params)

        val notice = IrcMessage.parse(":tmi.twitch.tv NOTICE * :Login authentication failed")!!
        assertNull(notice.channel)
        assertEquals("Login authentication failed", notice.trailing)
    }

    @Test
    fun clearChatWithoutTargetHasNoTrailing() {
        val msg = IrcMessage.parse("@room-id=1 :tmi.twitch.tv CLEARCHAT #channel")!!
        assertEquals("channel", msg.channel)
        assertNull(msg.trailing)
    }

    @Test
    fun rejectsGarbage() {
        assertNull(IrcMessage.parse(""))
        assertNull(IrcMessage.parse("@onlytags"))
    }

    @Test
    fun escapeRoundTrip() {
        val value = "a b;c\\d"
        val line = "@x=${IrcMessage.escapeTagValue(value)} :n PRIVMSG #c :t"
        assertEquals(value, IrcMessage.parse(line)!!.tag("x"))
    }

    // ---- tags looked up in the line ------------------------------------------------------------

    @Test
    fun aTagIsNotFoundInsideAnotherOnesName() {
        val msg = IrcMessage.parse("@user-id=5;badges=vip/1;badge-info=;id=abc :n PRIVMSG #c :t")!!
        assertEquals("abc", msg.tag("id"))
        assertEquals("vip/1", msg.tag("badges"))
        assertNull(msg.tag("badge"))
        assertNull(msg.tag("badge-info"))
        assertNull(msg.tag("missing"))
    }

    @Test
    fun aTagWithoutValueCountsAsEmpty() {
        val msg = IrcMessage.parse("@flag;last=1 :n PRIVMSG #c :t")!!
        assertNull(msg.tag("flag"))
        assertEquals("1", msg.tag("last"))
        assertEquals(mapOf("flag" to "", "last" to "1"), msg.tags)
    }

    @Test
    fun aLineWithoutTagsHasNone() {
        val msg = IrcMessage.parse(":n PRIVMSG #c :t")!!
        assertNull(msg.tag("id"))
        assertEquals(emptyMap<String, String>(), msg.tags)
    }

    @Test
    fun aLineIsReadInPlaceInsideAFrame() {
        val frame = "@id=1 :a!a@a PRIVMSG #c :first\r\n@id=2;color=#FF0000 :b!b@b PRIVMSG #c :second\r\n"
        val second = frame.indexOf("@id=2")
        val msg = IrcMessage.parse(frame, second, frame.length - 2)!!
        assertEquals("2", msg.tag("id"))
        assertEquals("#FF0000", msg.tag("color"))
        assertEquals("b", msg.nick)
        assertEquals("b!b@b", msg.prefix)
        assertEquals("second", msg.trailing)
    }
}
