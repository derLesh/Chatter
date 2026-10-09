package dev.chatter.app.emotes

import dev.chatter.app.chat.CheerSource
import dev.chatter.app.chat.EmoteSource
import dev.chatter.app.chat.MentionMatcher
import dev.chatter.app.chat.MessageBuilder
import dev.chatter.app.chat.Segment
import dev.chatter.app.irc.IrcMessage
import dev.chatter.app.net.AppJson
import dev.chatter.app.net.HelixCheermote
import dev.chatter.app.net.HelixList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CheermoteTest {
    private fun image(theme: String, bits: Int) =
        "https://d3aqoihi2n8ty8.cloudfront.net/actions/cheer/$theme/animated/$bits/2.gif"

    private fun tier(bits: Int, color: String, host: String? = null) = """
        {"min_bits": $bits, "id": "$bits", "color": "$color", "can_cheer": true,
         "images": {
           "dark": {"animated": {"1": "x", "2": "${host ?: image("dark", bits)}"}, "static": {}},
           "light": {"animated": {"2": "${image("light", bits)}"}}
         }}
    """

    private val answer = """
        {"data": [
          {"prefix": "Cheer", "type": "global_first_party", "order": 1,
           "tiers": [${tier(100, "#9c3ee8")}, ${tier(1, "#979797")}, ${tier(1000, "#1db2a5")}]},
          {"prefix": "4Head", "tiers": [${tier(1, "#979797", host = "https://evil.example/4head.gif")}]}
        ]}
    """

    private val cheermotes = AppJson.decodeFromString<HelixList<HelixCheermote>>(answer).data.mapNotNull(Cheermote::from)

    @Test
    fun readsTiersInOrderWithTheirColors() {
        val cheer = cheermotes.single()
        assertEquals("Cheer", cheer.prefix)
        assertEquals(listOf(1, 100, 1000), cheer.tiers.map { it.minBits })
        assertEquals(0xFF9C3EE8.toInt(), cheer.tiers[1].color)
        assertEquals(image("light", 100), cheer.tiers[1].lightUrl)
    }

    @Test
    fun leavesOutPicturesFromOtherHosts() {
        // 4Head's only tier points elsewhere, so nothing of it is left.
        assertEquals(listOf("Cheer"), cheermotes.map { it.prefix })
    }

    @Test
    fun picksTheHighestTierReached() {
        val cheer = cheermotes.single()
        assertEquals(1, cheer.tierFor(99)?.minBits)
        assertEquals(100, cheer.tierFor(100)?.minBits)
        assertEquals(1000, cheer.tierFor(25000)?.minBits)
        assertNull(cheer.tierFor(0))
    }

    private val builder = MessageBuilder(
        emotes = object : EmoteSource {
            override fun lookup(channelId: String?, word: String): Emote? = null
            override fun lookupOwnTwitch(channelId: String?, word: String): Emote? = null
        },
        badges = { _, _, _ -> emptyList() },
        cheers = CheerSource { _, prefix -> cheermotes.firstOrNull { it.prefix.equals(prefix, ignoreCase = true) } },
    )

    private fun build(text: String, bits: Int?) = builder.build(
        IrcMessage.parse("@id=x${bits?.let { ";bits=$it" }.orEmpty()} :user!user@user.tmi.twitch.tv PRIVMSG #chan :$text")!!,
        "lukas", "1", MentionMatcher("lukas", emptyList()),
    )!!

    @Test
    fun turnsCheersIntoCheermotes() {
        val item = build("cheer100 nice Cheer5 Cheer", bits = 105)
        assertEquals(105, item.bits)
        val cheers = item.segments.filterIsInstance<Segment.Cheer>()
        assertEquals(listOf(100, 5), cheers.map { it.amount })
        assertEquals(image("dark", 100), cheers[0].dark.url)
        assertEquals(0xFF979797.toInt(), cheers[1].color)
        // A prefix without an amount stays a word.
        assertEquals(Segment.Text(" Cheer"), item.segments.last())
    }

    @Test
    fun leavesCheersAloneWithoutBits() {
        val item = build("Cheer100", bits = null)
        assertEquals(0, item.bits)
        assertEquals(listOf<Segment>(Segment.Text("Cheer100")), item.segments)
    }

    @Test
    fun ignoresUnknownPrefixesAndZero() {
        val item = build("Kappa100 Cheer0", bits = 1)
        assertEquals(listOf<Segment>(Segment.Text("Kappa100 Cheer0")), item.segments)
    }
}
