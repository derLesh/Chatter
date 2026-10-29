package dev.chatter.app.chat

import dev.chatter.app.badges.Badge
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
    private val builder = MessageBuilder(emotes, { _, _, _ -> emptyList() })
    private val mentions = MentionMatcher("lukas", listOf("chatter"))

    private fun privmsg(text: String, tags: String = "") =
        IrcMessage.parse("@id=x;display-name=User${if (tags.isEmpty()) "" else ";$tags"} :user!user@user.tmi.twitch.tv PRIVMSG #chan :$text")!!

    private fun build(msg: IrcMessage) = builder.build(msg, "lukas", "1", mentions)!!

    @Test
    fun aMessageBuiltBeforeTheChannelsListsArriveGetsThemWhenBuiltAgain() {
        var loaded = false
        val channelEmotes = object : EmoteSource {
            override fun lookup(channelId: String?, word: String) = thirdParty[word]?.takeIf { loaded }
            override fun lookupOwnTwitch(channelId: String?, word: String) = null
            override fun complete(channelId: String?) = loaded
        }
        val channelBadges = object : BadgeSource {
            override fun resolve(channelId: String?, badgesTag: String?, userId: String?) =
                if (loaded) listOf(Badge("b", "Subscriber")) else emptyList()
            override fun complete(channelId: String?) = loaded
        }
        val item = MessageBuilder(channelEmotes, channelBadges)
            .build(privmsg("OMEGALUL hi", "badges=subscriber/12"), "lukas", "1", mentions)!!
        item.body.prepare()
        assertTrue(item.badges.isEmpty())

        loaded = true
        val rebuilt = item.copy(body = item.body.rebuilt())
        assertEquals("OMEGALUL", (rebuilt.segments[0] as Segment.EmoteSeg).emote.name)
        assertEquals(listOf("Subscriber"), rebuilt.badges.map { it.title })
    }

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
        // The emoji is 2 UTF-16 units but 1 code point, so Twitch puts the emote at 2.
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
        val builder = MessageBuilder(emotes, { _, _, _ -> emptyList() }, chatters)
        val mentioned = builder.build(privmsg("@forsen, @NoColor @elsewhere @stranger"), "lukas", "1", mentions)!!
            .segments.filterIsInstance<Segment.Mention>()

        // Known chatters keep their login (for coloring), even with trailing punctuation.
        assertEquals(listOf("forsen", "NoColor", null, null), mentioned.map { it.login })
        assertEquals(listOf(0xFF00FF00.toInt(), null, null, null), mentioned.map { it.color })
    }

    @Test
    fun mentionsOfUsersFromTheTwitchChatterList() {
        val chatters = ChatterRegistry().apply {
            setPresent("chan", mapOf("lurker" to "Lurker"))
            remember("chan", "Forsen", "Forsen", 0xFF00FF00.toInt())
        }
        val builder = MessageBuilder(emotes, { _, _, _ -> emptyList() }, chatters)
        val mentioned = builder.build(privmsg("@Lurker @stranger"), "lukas", "1", mentions)!!
            .segments.filterIsInstance<Segment.Mention>()

        // Listed but silent chatters are colored from their login, not skipped.
        assertEquals(listOf("Lurker", null), mentioned.map { it.login })
        assertEquals(listOf(null, null), mentioned.map { it.color })
        // Recently active chatters still come first in the suggestions.
        assertEquals(listOf("Forsen", "Lurker"), chatters.names("chan"))
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
    fun repliesKnowTheConversationTheyArePartOf() {
        val deep = build(privmsg("@lukas yes", "reply-parent-msg-id=p;reply-parent-user-login=lukas;reply-thread-parent-msg-id=root"))
        assertEquals("p", deep.reply!!.parentId)
        assertEquals("root", deep.reply!!.threadId)

        // An answer to the first message has no thread of its own: the parent is the start.
        val first = build(privmsg("@lukas yes", "reply-parent-msg-id=p;reply-parent-user-login=lukas"))
        assertEquals("p", first.reply!!.threadId)
    }

    @Test
    fun repliesStripTheMentionByDisplayName() {
        // Twitch writes the display name into the message, which may look nothing like the login.
        val tags = "reply-parent-msg-id=p;reply-parent-user-login=lukas;reply-parent-display-name=ルカス;" +
            "reply-parent-msg-body=hi;emotes=25:5-9"
        val item = build(privmsg("@ルカス Kappa yes", tags))
        assertEquals("Kappa yes", item.text)
        assertEquals("Kappa", (item.segments[0] as Segment.EmoteSeg).emote.name)
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
            MessageBuilder(source, { _, _, _ -> emptyList() }) { options }.build(privmsg(text, tags), "lukas", "1", mentions)!!.segments

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
    fun sharedChatMessagesFromAPartnerWearTheBadgesOfTheirChannel() {
        val asked = mutableListOf<Pair<String?, String?>>()
        val builder = MessageBuilder(emotes, { channelId, tag, _ -> asked += channelId to tag; emptyList() })
        val tags = "room-id=1;source-room-id=2;source-id=shared;badges=subscriber/0;" +
            "source-badges=moderator/1,subscriber/12;source-badge-info=subscriber/14"
        val item = builder.build(privmsg("hi", tags), "lukas", "1", mentions)!!
        item.body.prepare()

        assertEquals("2", item.sourceRoomId)
        assertEquals("shared", item.sharedId)
        // Looked up where they were earned, not where the copy arrived.
        assertEquals(listOf<Pair<String?, String?>>("2" to "moderator/1,subscriber/12"), asked)
    }

    @Test
    fun sharedChatMessagesWrittenHereAreNotFromAPartner() {
        val item = build(privmsg("hi", "room-id=1;source-room-id=1;source-id=x;source-badges=vip/1"))
        assertEquals(null, item.sourceRoomId)
        assertEquals("x", item.sharedId)

        val plain = build(privmsg("hi", "room-id=1"))
        assertEquals(null, plain.sourceRoomId)
        assertEquals(null, plain.sharedId)
    }

    @Test
    fun sharedChatNoticesAreMarkedAsWell() {
        val msg = IrcMessage.parse(
            "@id=n;login=sub;display-name=Sub;msg-id=sharedchatnotice;source-msg-id=sub;room-id=1;" +
                "source-room-id=2;source-id=s;system-msg=Sub\\ssubscribed. :tmi.twitch.tv USERNOTICE #chan",
        )!!
        val item = builder.build(msg, "lukas", "1", mentions)!!
        assertEquals(MessageKind.UserNotice, item.kind)
        assertEquals("2", item.sourceRoomId)
        assertEquals("s", item.sharedId)
    }

    @Test
    fun parseColor() {
        assertEquals(0xFFFF0000.toInt(), MessageBuilder.parseColor("#FF0000"))
        assertEquals(null, MessageBuilder.parseColor(""))
        assertEquals(null, MessageBuilder.parseColor("#XYZ"))
    }

    @Test
    fun theAnsweredMessageIsQuotedWithItsEmotesAndLinks() {
        val tags = "reply-parent-msg-id=p;reply-parent-user-login=ole;reply-parent-display-name=ole;" +
            "reply-parent-msg-body=ole\\sOMEGALUL\\shttps://i.imgur.com/abc.png"
        val item = build(privmsg("@ole nice", tags))
        assertEquals(
            listOf(
                Segment.Text("ole "),
                Segment.EmoteSeg(thirdParty.getValue("OMEGALUL")),
                Segment.Text(" "),
                Segment.Link("https://i.imgur.com/abc.png", "https://i.imgur.com/abc.png"),
            ),
            item.quote,
        )
    }

    @Test
    fun aMessageThatAnswersNothingQuotesNothing() {
        assertTrue(build(privmsg("OMEGALUL")).quote.isEmpty())
    }

    /** The example from Twitch's docs. */
    @Test
    fun aGifFromTheTagReplacesItsTitle() {
        val url = "https://media4.giphy.com/media/joSNxeswxuc74Juo8X/giphy.gif?" +
            "cid=095d7a5dzizsiwgabonagkmigggv8v1spfai91ac3x0dsiy0&ep=v1_gifs_trending&rid=giphy.gif&ct=g"
        val item = build(privmsg("[Y A Y Yes GIF by Djemilah Birnie]", "gifs=0-33|joSNxeswxuc74Juo8X|$url"))
        assertEquals(listOf<Segment>(Segment.Gif("[Y A Y Yes GIF by Djemilah Birnie]", url)), item.segments)
        assertEquals("[Y A Y Yes GIF by Djemilah Birnie]", item.text)
    }

    @Test
    fun aGifInTheMiddleKeepsTheTextAroundIt() {
        val url = "https://media.giphy.com/media/b/giphy.gif"
        val item = build(privmsg("😀 [Kappa GIF] OMEGALUL", "gifs=2-12|b|$url"))
        assertEquals(
            listOf(Segment.Text("😀 "), Segment.Gif("[Kappa GIF]", url), Segment.Text(" "), Segment.EmoteSeg(thirdParty.getValue("OMEGALUL"))),
            item.segments,
        )
    }

    @Test
    fun aGifFromAnywhereButGiphyStaysText() {
        val item = build(privmsg("[Yes GIF]", "gifs=0-8|x|https://evil.example/giphy.gif"))
        assertEquals(listOf<Segment>(Segment.Text("[Yes GIF]")), item.segments)
    }

    @Test
    fun aGifInAReplyIsShiftedLikeEmotes() {
        val url = "https://media.giphy.com/media/b/giphy.gif"
        val tags = "reply-parent-msg-id=p;reply-parent-user-login=ole;reply-parent-display-name=ole;gifs=5-13|b|$url"
        val item = build(privmsg("@ole [Yes GIF]", tags))
        assertEquals(listOf<Segment>(Segment.Gif("[Yes GIF]", url)), item.segments)
    }

    @Test
    fun powerUpsAreReadFromTheMessageId() {
        assertEquals(PowerUp.GiantEmote, build(privmsg("Kappa", "msg-id=gigantified-emote-message;emotes=25:0-4")).powerUp)
        assertEquals(PowerUp.Highlighted, build(privmsg("hi", "msg-id=highlighted-message")).powerUp)
        assertEquals(PowerUp.Effect, build(privmsg("hi", "msg-id=animated-message;animation-id=simmer")).powerUp)
        assertEquals(null, build(privmsg("hi")).powerUp)
    }

    @Test
    fun aRewardMessageKnowsItsReward() {
        assertEquals("r1", build(privmsg("hydrate!", "custom-reward-id=r1")).rewardId)
        assertEquals(null, build(privmsg("hi", "custom-reward-id=")).rewardId)
    }

    @Test
    fun aWatchStreakCountsItsStreams() {
        fun notice(tags: String) = IrcMessage.parse(
            "@id=n;login=user;display-name=User;$tags;system-msg=User\\swatched\\s5\\sconsecutive\\sstreams :tmi.twitch.tv USERNOTICE #chan",
        )!!
        val streak = build(notice("msg-id=viewermilestone;msg-param-category=watch-streak;msg-param-value=5"))
        assertEquals(5, streak.watchStreak)
        assertEquals(MessageKind.UserNotice, streak.kind)
        assertEquals(null, build(notice("msg-id=viewermilestone;msg-param-category=other;msg-param-value=5")).watchStreak)
        assertEquals(null, build(notice("msg-id=resub;msg-param-value=5")).watchStreak)
    }
}
