package dev.chatter.app.chat

import dev.chatter.app.emotes.Emote
import dev.chatter.app.emotes.EmoteProvider
import dev.chatter.app.irc.IrcMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageBuilderTest {
    private val thirdParty = mapOf(
        "OMEGALUL" to Emote("OMEGALUL", "1", "u1", EmoteProvider.Bttv),
        "RainTime" to Emote("RainTime", "2", "u2", EmoteProvider.SevenTv, zeroWidth = true),
        "LULW" to Emote("LULW", "3", "u3", EmoteProvider.Ffz),
    )
    private val own = mapOf("Kappa" to Emote("Kappa", "25", "k", EmoteProvider.Twitch))

    private val emotes = object : EmoteSource {
        override fun lookup(channelId: String?, word: String) = thirdParty[word]
        override fun lookupOwnTwitch(channelId: String?, word: String) = own[word]
    }
    private val builder = MessageBuilder(emotes, { _, _ -> emptyList() })
    private val mentions = MentionMatcher("lukas", listOf("chatter"))

    private fun privmsg(text: String, tags: String = "") =
        IrcMessage.parse("@id=x;display-name=User${if (tags.isEmpty()) "" else ";$tags"} :user!user@user.tmi.twitch.tv PRIVMSG #chan :$text")!!

    private fun build(msg: IrcMessage) = builder.build(msg, "lukas", "1", mentions)!!

    @Test
    fun twitchEmotesFromTag() {
        val item = build(privmsg("Kappa hi Kappa", "emotes=25:0-4,9-13"))
        assertEquals(3, item.segments.size)
        assertEquals("Kappa", (item.segments[0] as Segment.EmoteSeg).emote.name)
        assertEquals(Segment.Text(" hi "), item.segments[1])
        assertEquals("25", (item.segments[2] as Segment.EmoteSeg).emote.id)
    }

    @Test
    fun twitchEmotePositionsCountCodePoints() {
        // The emoji is 2 UTF-16 chars but 1 code point, so Twitch says the emote starts at 2.
        val text = "😀 Kappa"
        val item = build(privmsg(text, "emotes=25:2-6"))
        val emote = item.segments.filterIsInstance<Segment.EmoteSeg>().single()
        assertEquals("Kappa", emote.emote.name)
    }

    @Test
    fun invalidEmoteRangesAreIgnored() {
        val item = build(privmsg("hi", "emotes=25:0-10"))
        assertEquals(listOf<Segment>(Segment.Text("hi")), item.segments)
    }

    @Test
    fun thirdPartyEmotesAndZeroWidth() {
        val item = build(privmsg("OMEGALUL RainTime nice LULW"))
        val first = item.segments[0] as Segment.EmoteSeg
        assertEquals("OMEGALUL", first.emote.name)
        assertEquals(listOf("RainTime"), first.overlays.map { it.name })
        assertEquals(Segment.Text(" nice "), item.segments[1])
        assertEquals("LULW", (item.segments[2] as Segment.EmoteSeg).emote.name)
    }

    @Test
    fun zeroWidthWithoutBaseIsNormalEmote() {
        val item = build(privmsg("RainTime"))
        val seg = item.segments.single() as Segment.EmoteSeg
        assertEquals("RainTime", seg.emote.name)
        assertTrue(seg.overlays.isEmpty())
    }

    @Test
    fun linksAndMentions() {
        val item = build(privmsg("see twitch.tv/forsen and https://x.com/a @someone e.g."))
        val links = item.segments.filterIsInstance<Segment.Link>()
        assertEquals(listOf("https://twitch.tv/forsen", "https://x.com/a"), links.map { it.url })
        assertEquals("twitch.tv/forsen", links[0].text)
        assertEquals(listOf("@someone"), item.segments.filterIsInstance<Segment.Mention>().map { it.name })
    }

    @Test
    fun mentionsOfActiveChattersCarryTheirColor() {
        val chatters = ChatterRegistry().apply {
            remember("chan", "Forsen", "Forsen", 0xFF00FF00.toInt())
            remember("chan", "nocolor", "NoColor", null)
            remember("other", "elsewhere", "Elsewhere", 0xFFFF0000.toInt())
        }
        val builder = MessageBuilder(emotes, { _, _ -> emptyList() }, chatters)
        val mentioned = builder.build(privmsg("@forsen, @NoColor @elsewhere @stranger"), "lukas", "1", mentions)!!
            .segments.filterIsInstance<Segment.Mention>()

        // Known chatters keep their login (so the row can color them), even with trailing punctuation.
        assertEquals(listOf("forsen", "NoColor", null, null), mentioned.map { it.login })
        assertEquals(listOf(0xFF00FF00.toInt(), null, null, null), mentioned.map { it.color })
    }

    @Test
    fun actionMessages() {
        val item = build(privmsg("\u0001ACTION waves\u0001"))
        assertEquals(MessageKind.Action, item.kind)
        assertEquals("waves", item.text)
    }

    @Test
    fun mentionDetection() {
        assertTrue(build(privmsg("hey @Lukas how are you")).isMention)
        assertTrue(build(privmsg("LUKAS!")).isMention)
        assertTrue(build(privmsg("I love chatter")).isMention)
        assertFalse(build(privmsg("lukasz is here")).isMention)
    }

    @Test
    fun ownMessagesAreNeverMentions() {
        val msg = IrcMessage.parse("@id=y :lukas!lukas@lukas.tmi.twitch.tv PRIVMSG #chan :lukas talking to himself")!!
        val item = builder.build(msg, "lukas", "1", mentions)!!
        assertTrue(item.isOwn)
        assertFalse(item.isMention)
    }

    @Test
    fun repliesStripTheLeadingMentionAndShiftEmotes() {
        val tags = "reply-parent-msg-id=p;reply-parent-user-login=lukas;reply-parent-display-name=Lukas;" +
            "reply-parent-msg-body=hi\\sthere;emotes=25:7-11"
        val item = build(privmsg("@lukas Kappa yes", tags))
        assertEquals("Kappa yes", item.text)
        assertEquals("Kappa", (item.segments[0] as Segment.EmoteSeg).emote.name)
        assertEquals("hi there", item.reply!!.parentBody)
        assertTrue("reply to me counts as mention", item.isMention)
    }

    @Test
    fun ownEchoUsesUserEmotes() {
        val item = builder.buildOwn("chan", "Kappa OMEGALUL", mapOf("display-name" to "Lukas", "color" to "#00FF00"), "lukas", "1", null)
        assertEquals(listOf("Kappa", "OMEGALUL"), item.segments.filterIsInstance<Segment.EmoteSeg>().map { it.emote.name })
        assertEquals("Lukas", item.displayName)
        assertEquals(0xFF00FF00.toInt(), item.color)
    }

    @Test
    fun emoteOptions() {
        val unlisted = Emote("Secret", "4", "u4", EmoteProvider.SevenTv, unlisted = true)
        val source = object : EmoteSource {
            override fun lookup(channelId: String?, word: String) = if (word == "Secret") unlisted else thirdParty[word]
            override fun lookupOwnTwitch(channelId: String?, word: String) = null
        }
        fun segments(options: EmoteOptions, text: String, tags: String = "") =
            MessageBuilder(source, { _, _ -> emptyList() }) { options }.build(privmsg(text, tags), "lukas", "1", mentions)!!.segments

        // Emotes off: everything is text, also Twitch emotes from the tag.
        assertEquals(listOf<Segment>(Segment.Text("Kappa OMEGALUL")), segments(EmoteOptions(enabled = false), "Kappa OMEGALUL", "emotes=25:0-4"))
        // Zero-width off: the overlay becomes a normal emote next to the base.
        val noZw = segments(EmoteOptions(zeroWidth = false), "OMEGALUL RainTime")
        assertEquals(listOf("OMEGALUL", "RainTime"), noZw.filterIsInstance<Segment.EmoteSeg>().map { it.emote.name })
        // Unlisted 7TV emotes are text unless enabled.
        assertEquals(listOf<Segment>(Segment.Text("Secret")), segments(EmoteOptions(showUnlisted = false), "Secret"))
        assertTrue(segments(EmoteOptions(showUnlisted = true), "Secret").single() is Segment.EmoteSeg)
    }

    @Test
    fun parseColor() {
        assertEquals(0xFFFF0000.toInt(), MessageBuilder.parseColor("#FF0000"))
        assertEquals(null, MessageBuilder.parseColor(""))
        assertEquals(null, MessageBuilder.parseColor("#XYZ"))
    }
}
