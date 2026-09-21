package dev.chatter.app.badges

import dev.chatter.app.net.ChatterSupporter
import dev.chatter.app.net.ChatterSupporters
import dev.chatter.app.net.ChatterinoBadge
import dev.chatter.app.net.ChatterinoBadges
import dev.chatter.app.net.HelixBadgeSet
import dev.chatter.app.net.HelixBadgeVersion
import dev.chatter.app.net.ThirdPartyBadgeApi
import dev.chatter.app.net.TwitchBadgeApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The badge repository against lists that can refuse to answer — which is what most of its rules
 * are about, now that one of the four is gone for good and another only arrives over a socket.
 */
class BadgeRepositoryTest {
    private val twitch = FakeTwitchBadges()
    private val others = FakeOtherClients()
    private var clock = 1_000_000_000L
    private val badges = BadgeRepository(twitch, others) { clock }

    private val titles = SupporterTitles(once = "Supporter", monthly = "Supporter, every month")

    // ---- the fakes --------------------------------------------------------------------------

    private class FakeTwitchBadges : TwitchBadgeApi {
        var global: List<HelixBadgeSet>? = listOf(badgeSet("subscriber", "0", "Subscriber"))
        var channel: List<HelixBadgeSet>? = listOf(badgeSet("subscriber", "0", "Subscriber of this channel"))
        var globalCalls = 0
        var channelCalls = 0

        override suspend fun globalBadges(): List<HelixBadgeSet> {
            globalCalls++
            return global ?: throw IOException("Twitch is down")
        }

        override suspend fun channelBadges(channelId: String): List<HelixBadgeSet> {
            channelCalls++
            return channel ?: throw IOException("Twitch is down")
        }

        companion object {
            fun badgeSet(setId: String, version: String, title: String) = HelixBadgeSet(
                setId = setId,
                versions = listOf(HelixBadgeVersion(id = version, title = title, url2x = "https://twitch.invalid/$setId")),
            )
        }
    }

    private class FakeOtherClients : ThirdPartyBadgeApi {
        var chatterino: List<String>? = listOf("42")
        var supporters: List<ChatterSupporter>? = listOf(ChatterSupporter(twitch = "7"))
        var chatterinoCalls = 0
        var supporterCalls = 0

        override suspend fun chatterinoBadges(): ChatterinoBadges {
            chatterinoCalls++
            val users = chatterino ?: throw IOException("Chatterino is down")
            return ChatterinoBadges(listOf(ChatterinoBadge(tooltip = "Chatterino Fan", image2 = "https://c.invalid/2", users = users)))
        }

        override suspend fun chatterSupporters(): ChatterSupporters {
            supporterCalls++
            return ChatterSupporters(supporters ?: throw IOException("the list is not there"))
        }
    }

    // ---- reading the tag ----------------------------------------------------------------------

    @Test
    fun theChannelsOwnVersionOfABadgeWinsOverTheGlobalOne() = runTest {
        badges.loadGlobal()
        badges.loadChannel("22484632")

        val here = badges.resolve("22484632", "subscriber/0", userId = null)
        assertEquals(listOf("Subscriber of this channel"), here.map { it.title })

        val elsewhere = badges.resolve("999", "subscriber/0", userId = null)
        assertEquals(listOf("Subscriber"), elsewhere.map { it.title })
    }

    @Test
    fun aBadgeNobodyKnowsIsLeftOut() = runTest {
        badges.loadGlobal()
        assertTrue(badges.resolve(null, "unheard-of/1", userId = null).isEmpty())
    }

    @Test
    fun aProviderTheUserTurnedOffIsLeftOut() = runTest {
        badges.loadGlobal()
        badges.loadThirdParty(titles)
        assertEquals(2, badges.resolve(null, "subscriber/0", userId = "42").size)

        badges.enabled = setOf(BadgeProvider.Twitch)
        assertEquals(listOf(BadgeProvider.Twitch), badges.resolve(null, "subscriber/0", userId = "42").map { it.provider })
    }

    // ---- lists that did not answer --------------------------------------------------------------

    @Test
    fun theGlobalBadgesAreTriedAgainUntilTheyAreThere() = runTest {
        twitch.global = null
        badges.loadGlobal()
        assertTrue("without them no message has any badge", badges.resolve(null, "subscriber/0", userId = null).isEmpty())

        twitch.global = listOf(FakeTwitchBadges.badgeSet("subscriber", "0", "Subscriber"))
        clock += 6 * 60_000L
        badges.retryMissing(titles)
        assertEquals(listOf("Subscriber"), badges.resolve(null, "subscriber/0", userId = null).map { it.title })

        val calls = twitch.globalCalls
        clock += 6 * 60_000L
        badges.retryMissing(titles)
        assertEquals("once they are in, they are not asked for again", calls, twitch.globalCalls)
    }

    @Test
    fun aListThatAnsweredIsNotThrownAwayWhenAnotherIsRetried() = runTest {
        others.chatterino = null
        badges.loadThirdParty(titles)
        assertEquals(listOf("Supporter"), badges.resolve(null, null, userId = "7").map { it.title })

        others.chatterino = listOf("42")
        clock += 6 * 60_000L
        badges.retryMissing(titles)

        assertEquals("the one that answered is still here", listOf("Supporter"), badges.resolve(null, null, userId = "7").map { it.title })
        assertEquals(listOf("Chatterino Fan"), badges.resolve(null, null, userId = "42").map { it.title })
        assertEquals("and was not asked twice", 1, others.supporterCalls)
    }

    @Test
    fun aChannelWhoseBadgesFailedIsTriedAgain() = runTest {
        twitch.channel = null
        badges.loadChannel("22484632")
        val afterFirst = twitch.channelCalls

        twitch.channel = listOf(FakeTwitchBadges.badgeSet("subscriber", "0", "Subscriber of this channel"))
        clock += 6 * 60_000L
        badges.retryMissing(titles)
        assertTrue(twitch.channelCalls > afterFirst)
        assertEquals(
            listOf("Subscriber of this channel"),
            badges.resolve("22484632", "subscriber/0", userId = null).map { it.title },
        )

        val calls = twitch.channelCalls
        clock += 6 * 60_000L
        badges.retryMissing(titles)
        assertEquals("a channel that answered is left alone", calls, twitch.channelCalls)
    }

    @Test
    fun aProviderThatIsDownIsNotAskedAgainStraightAway() = runTest {
        twitch.global = null
        badges.retryMissing(titles)
        val calls = twitch.globalCalls

        clock += 60_000L
        badges.retryMissing(titles)
        assertEquals("coming back to the app every minute must not mean asking every minute", calls, twitch.globalCalls)

        clock += 5 * 60_000L
        badges.retryMissing(titles)
        assertTrue(twitch.globalCalls > calls)
    }

    // ---- who supports Chatter, and how ------------------------------------------------------------

    @Test
    fun theBadgeSaysWhetherSomebodySupportsOnceOrEveryMonth() = runTest {
        others.supporters = listOf(
            ChatterSupporter(twitch = "7", kind = ChatterSupporter.KIND_ONCE),
            ChatterSupporter(twitch = "8", kind = ChatterSupporter.KIND_MONTHLY),
        )
        badges.loadThirdParty(titles)

        assertEquals(listOf("Supporter"), badges.resolve(null, null, userId = "7").map { it.title })
        assertEquals(listOf("Supporter, every month"), badges.resolve(null, null, userId = "8").map { it.title })
    }

    @Test
    fun aKindThisVersionDoesNotKnowStillWearsTheBadge() = runTest {
        // Whatever GitHub Sponsors grows into later must not leave an older app with nothing.
        others.supporters = listOf(ChatterSupporter(twitch = "7", kind = "yearly-gold-whatever"))
        badges.loadThirdParty(titles)
        assertEquals(listOf("Supporter"), badges.resolve(null, null, userId = "7").map { it.title })
    }

    @Test
    fun anEntryWithoutATwitchAccountIsLeftOut() = runTest {
        others.supporters = listOf(ChatterSupporter(twitch = "", kind = "once"), ChatterSupporter(twitch = "7"))
        badges.loadThirdParty(titles)
        assertEquals(1, badges.resolve(null, null, userId = "7").size)
        assertTrue(badges.resolve(null, null, userId = "").isEmpty())
    }

    // ---- what 7TV pushes over the socket ---------------------------------------------------------

    @Test
    fun aSevenTvBadgeShowsUpOnceItIsKnownAndWorn() = runTest {
        badges.sevenTvWearer("42", "cosmetic-1", worn = true)
        assertTrue("the badge itself has not been described yet", badges.resolve(null, null, userId = "42").isEmpty())

        badges.sevenTvBadge("cosmetic-1", name = "Subscriber", tooltip = "7TV Subscriber (1 Year)")
        val shown = badges.resolve(null, null, userId = "42")
        assertEquals(listOf("7TV Subscriber (1 Year)"), shown.map { it.title })
        assertEquals(listOf(BadgeProvider.SevenTv), shown.map { it.provider })
        assertEquals("https://cdn.7tv.app/badge/cosmetic-1/2x.webp", shown.single().url)
    }

    @Test
    fun takingASevenTvBadgeOffTakesItOffTheMessagesToo() = runTest {
        badges.sevenTvBadge("cosmetic-1", name = "Subscriber", tooltip = "7TV Subscriber")
        badges.sevenTvWearer("42", "cosmetic-1", worn = true)
        assertEquals(1, badges.resolve(null, null, userId = "42").size)

        badges.sevenTvWearer("42", "cosmetic-1", worn = false)
        assertTrue(badges.resolve(null, null, userId = "42").isEmpty())
    }

    @Test
    fun losingOneBadgeDoesNotTakeTheNewOneWithIt() = runTest {
        badges.sevenTvBadge("old", name = "Old", tooltip = "Old")
        badges.sevenTvBadge("new", name = "New", tooltip = "New")
        badges.sevenTvWearer("42", "old", worn = true)
        badges.sevenTvWearer("42", "new", worn = true)

        // 7TV says the old entitlement is gone after handing out the new one.
        badges.sevenTvWearer("42", "old", worn = false)
        assertEquals(listOf("New"), badges.resolve(null, null, userId = "42").map { it.title })
    }
}
