package me.lesh.chatter.badges

import me.lesh.chatter.net.ChatterBadge
import me.lesh.chatter.net.ChatterBadgeApi
import me.lesh.chatter.net.ChatterBadges
import me.lesh.chatter.net.ChatterSupporters
import me.lesh.chatter.net.ChatterinoBadge
import me.lesh.chatter.net.ChatterinoBadges
import me.lesh.chatter.net.HelixBadgeSet
import me.lesh.chatter.net.HelixBadgeVersion
import me.lesh.chatter.net.NotLoggedInException
import me.lesh.chatter.net.ServiceTrouble
import me.lesh.chatter.net.ThirdPartyBadgeApi
import me.lesh.chatter.net.TwitchBadgeApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The badge repository against lists that may not answer. */
class BadgeRepositoryTest {
    private val twitch = FakeTwitchBadges()
    private val others = FakeOtherClients()
    private val chatter = FakeChatterApi()
    private val trouble = ServiceTrouble()
    private var clock = 1_789_992_000_000L
    private val badges = BadgeRepository(twitch, others, chatter, supporterTitle = { "Supporter" }, trouble) { clock }

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
                versions = listOf(HelixBadgeVersion(id = version, title = title, url2x = "https://static-cdn.jtvnw.net/badges/v1/$setId/2")),
            )
        }
    }

    private class FakeOtherClients : ThirdPartyBadgeApi {
        var chatterino: List<String>? = listOf("42")
        var chatterinoCalls = 0

        override suspend fun chatterinoBadges(): ChatterinoBadges {
            chatterinoCalls++
            val users = chatterino ?: throw IOException("Chatterino is down")
            return ChatterinoBadges(listOf(ChatterinoBadge(tooltip = "Chatterino Fan", image2 = "https://fourtf.com/chatterino/badges/fan.png", users = users)))
        }
    }

    private class FakeChatterApi : ChatterBadgeApi {
        var loggedIn = true
        /** Null makes the API unreachable. */
        var supporters: List<String>? = listOf("7")
        var badges = listOf(
            ChatterBadge(
                id = "chatter",
                title = "Chatter",
                images = mapOf(
                    "dark" to mapOf("1x" to "/badges/chatter/dark/1x.png", "2x" to "/badges/chatter/dark/2x.png"),
                    "light" to mapOf("1x" to "/badges/chatter/light/1x.png", "2x" to "/badges/chatter/light/2x.png"),
                ),
            ),
        )
        var supporterCalls = 0

        override suspend fun supporters(): ChatterSupporters {
            if (!loggedIn) throw NotLoggedInException()
            supporterCalls++
            return ChatterSupporters(supporters ?: throw IOException("the API is down"))
        }

        override suspend fun badges() = ChatterBadges(badges)

        override fun imageUrl(path: String) = "https://api.lesh.me$path"
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
        badges.loadThirdParty()
        assertEquals(2, badges.resolve(null, "subscriber/0", userId = "42").size)

        badges.enabled = setOf(BadgeProvider.Twitch)
        assertEquals(listOf(BadgeProvider.Twitch), badges.resolve(null, "subscriber/0", userId = "42").map { it.provider })
    }

    // ---- kinds of badges ----------------------------------------------------------------------

    @Test
    fun everyVersionOfATwitchBadgeIsOneKind() = runTest {
        twitch.global = listOf(
            FakeTwitchBadges.badgeSet("subscriber", "0", "Subscriber"),
            FakeTwitchBadges.badgeSet("subscriber", "12", "1-Year Subscriber"),
        )
        badges.loadGlobal()

        val kinds = badges.resolve(null, "subscriber/0", userId = null) + badges.resolve(null, "subscriber/12", userId = null)
        assertEquals(setOf("Twitch/subscriber"), kinds.map { it.kind }.toSet())
    }

    @Test
    fun theSameNameFromAnotherProviderIsAnotherKind() {
        assertTrue(Badge.kindOf(BadgeProvider.Twitch, "subscriber") != Badge.kindOf(BadgeProvider.SevenTv, "subscriber"))
    }

    @Test
    fun everyLoadedKindIsKnownOnce() = runTest {
        twitch.global = listOf(
            FakeTwitchBadges.badgeSet("subscriber", "12", "1-Year Subscriber"),
            FakeTwitchBadges.badgeSet("subscriber", "0", "Subscriber"),
            FakeTwitchBadges.badgeSet("bits", "100", "cheer 100"),
        )
        badges.loadGlobal()
        badges.loadChannel("22484632")
        badges.loadThirdParty()
        badges.sevenTvBadge("cosmetic-1", name = "Subscriber", tooltip = "7TV Subscriber")

        val known = badges.knownKinds()
        assertEquals(
            setOf("Twitch/subscriber", "Twitch/bits", "Chatterino/Chatterino Fan", "Chatter/supporter", "SevenTv/cosmetic-1"),
            known.map { it.kind }.toSet(),
        )
        assertEquals("the first version stands for the set", "Subscriber", known.first { it.kind == "Twitch/subscriber" }.title)
    }

    // ---- lists that did not answer --------------------------------------------------------------

    @Test
    fun theGlobalBadgesAreTriedAgainUntilTheyAreThere() = runTest {
        twitch.global = null
        badges.loadGlobal()
        assertTrue("without them no message has any badge", badges.resolve(null, "subscriber/0", userId = null).isEmpty())

        twitch.global = listOf(FakeTwitchBadges.badgeSet("subscriber", "0", "Subscriber"))
        clock += 6 * 60_000L
        badges.retryMissing()
        assertEquals(listOf("Subscriber"), badges.resolve(null, "subscriber/0", userId = null).map { it.title })

        val calls = twitch.globalCalls
        clock += 6 * 60_000L
        badges.retryMissing()
        assertEquals("once they are in, they are not asked for again", calls, twitch.globalCalls)
    }

    @Test
    fun aListThatAnsweredIsNotThrownAwayWhenAnotherIsRetried() = runTest {
        others.chatterino = null
        badges.loadThirdParty()
        assertEquals(listOf("Supporter"), badges.resolve(null, null, userId = "7").map { it.title })

        others.chatterino = listOf("42")
        clock += 6 * 60_000L
        badges.retryMissing()

        assertEquals("the one that answered is still here", listOf("Supporter"), badges.resolve(null, null, userId = "7").map { it.title })
        assertEquals(listOf("Chatterino Fan"), badges.resolve(null, null, userId = "42").map { it.title })
        assertEquals("and was not asked twice", 1, chatter.supporterCalls)
    }

    @Test
    fun aChannelWhoseBadgesFailedIsTriedAgain() = runTest {
        twitch.channel = null
        badges.loadChannel("22484632")
        val afterFirst = twitch.channelCalls

        twitch.channel = listOf(FakeTwitchBadges.badgeSet("subscriber", "0", "Subscriber of this channel"))
        clock += 6 * 60_000L
        badges.retryMissing()
        assertTrue(twitch.channelCalls > afterFirst)
        assertEquals(
            listOf("Subscriber of this channel"),
            badges.resolve("22484632", "subscriber/0", userId = null).map { it.title },
        )

        val calls = twitch.channelCalls
        clock += 6 * 60_000L
        badges.retryMissing()
        assertEquals("a channel that answered is left alone", calls, twitch.channelCalls)
    }

    @Test
    fun aProviderThatIsDownIsNotAskedAgainStraightAway() = runTest {
        twitch.global = null
        badges.retryMissing()
        val calls = twitch.globalCalls

        clock += 60_000L
        badges.retryMissing()
        assertEquals("coming back to the app every minute must not mean asking every minute", calls, twitch.globalCalls)

        clock += 5 * 60_000L
        badges.retryMissing()
        assertTrue(twitch.globalCalls > calls)
    }

    // ---- who supports Chatter ----------------------------------------------------------------------

    @Test
    fun aSupporterWearsTheBadgeTheApiServes() = runTest {
        badges.loadThirdParty()

        val shown = badges.resolve(null, null, userId = "7").single()
        assertEquals("Supporter", shown.title)
        assertEquals(BadgeProvider.Chatter, shown.provider)
        assertEquals("the size Twitch's badges are loaded in", "https://api.lesh.me/badges/chatter/dark/2x.png", shown.url)
        assertEquals("https://api.lesh.me/badges/chatter/light/2x.png", shown.urlOnLight)
    }

    @Test
    fun aGuestIsNotToldTheApiIsDown() = runTest {
        val said = mutableListOf<String>()
        val watching = launch { trouble.unreachable.collect { said += it } }
        runCurrent()

        // The API checks the Twitch token; a guest has none to send.
        chatter.loggedIn = false
        badges.loadThirdParty()
        runCurrent()
        assertTrue(said.isEmpty())

        chatter.loggedIn = true
        clock += 6 * 60_000L
        badges.retryMissing()
        assertEquals("asked for once there is a login", listOf("Supporter"), badges.resolve(null, null, userId = "7").map { it.title })
        watching.cancel()
    }

    @Test
    fun anApiThatIsDownIsNamedAndTriedAgain() = runTest {
        val said = mutableListOf<String>()
        val watching = launch { trouble.unreachable.collect { said += it } }
        runCurrent()

        chatter.supporters = null
        badges.loadThirdParty()
        runCurrent()
        assertEquals(listOf(ServiceTrouble.CHATTER_API), said)

        chatter.supporters = listOf("7")
        clock += 6 * 60_000L
        badges.retryMissing()
        assertEquals(1, badges.resolve(null, null, userId = "7").size)
        watching.cancel()
    }

    @Test
    fun withoutTheBadgePictureNobodyWearsIt() = runTest {
        // A badge without a picture would be an empty gap in every line of the supporter.
        chatter.badges = emptyList()
        badges.loadThirdParty()
        assertTrue(badges.resolve(null, null, userId = "7").isEmpty())

        chatter.badges = listOf(ChatterBadge(id = "chatter", images = mapOf("light" to mapOf("4x" to "/badges/chatter/light/4x.png"))))
        clock += 6 * 60_000L
        badges.retryMissing()
        assertEquals(
            "any size and theme will do",
            "https://api.lesh.me/badges/chatter/light/4x.png",
            badges.resolve(null, null, userId = "7").single().url,
        )
    }

    @Test
    fun anEmptyIdIsLeftOut() = runTest {
        chatter.supporters = listOf("", "7")
        badges.loadThirdParty()
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

        // 7TV removes the old entitlement after granting the new one.
        badges.sevenTvWearer("42", "old", worn = false)
        assertEquals(listOf("New"), badges.resolve(null, null, userId = "42").map { it.title })
    }
}
