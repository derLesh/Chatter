package me.lesh.chatter.badges

import me.lesh.chatter.net.ChatterBadge
import me.lesh.chatter.net.ChatterBadgeApi
import me.lesh.chatter.net.ChatterBadges
import me.lesh.chatter.net.ChatterSupporters
import me.lesh.chatter.net.ChatterinoBadge
import me.lesh.chatter.net.ChatterinoBadges
import me.lesh.chatter.net.FfzBadge
import me.lesh.chatter.net.FfzBadges
import me.lesh.chatter.net.HelixBadgeSet
import me.lesh.chatter.net.HelixBadgeVersion
import me.lesh.chatter.net.ServiceTrouble
import me.lesh.chatter.net.ThirdPartyBadgeApi
import me.lesh.chatter.net.TwitchBadgeApi
import kotlinx.coroutines.CompletableDeferred
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
        var channelGate: CompletableDeferred<Unit>? = null

        override suspend fun globalBadges(): List<HelixBadgeSet> {
            globalCalls++
            return global ?: throw IOException("Twitch is down")
        }

        override suspend fun channelBadges(channelId: String): List<HelixBadgeSet> {
            channelCalls++
            channelGate?.await()
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
        /** Wearers of FFZ's supporter badge, unsorted as FFZ sends them; null makes FFZ unreachable. */
        var ffzSupporters: LongArray? = longArrayOf(900, 77, 300)
        var ffzCalls = 0

        override suspend fun ffzBadges(): FfzBadges {
            ffzCalls++
            val users = ffzSupporters ?: throw IOException("FFZ is down")
            val supporter = FfzBadge(
                id = 3, name = "supporter", title = "FFZ Supporter", color = "#755000",
                urls = mapOf("1" to "https://cdn.frankerfacez.com/badge/3/1", "2" to "https://cdn.frankerfacez.com/badge/3/2"),
            )
            val elsewhere = FfzBadge(id = 9, name = "elsewhere", urls = mapOf("2" to "https://example.com/badge.png"))
            return FfzBadges(listOf(supporter, elsewhere), mapOf("3" to users, "9" to longArrayOf(77)))
        }

        override suspend fun chatterinoBadges(): ChatterinoBadges {
            chatterinoCalls++
            val users = chatterino ?: throw IOException("Chatterino is down")
            return ChatterinoBadges(listOf(ChatterinoBadge(tooltip = "Chatterino Fan", image2 = "https://fourtf.com/chatterino/badges/fan.png", users = users)))
        }
    }

    private class FakeChatterApi : ChatterBadgeApi {
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
            setOf("Twitch/subscriber", "Twitch/bits", "Chatterino/Chatterino Fan", "Ffz/supporter", "Chatter/supporter", "SevenTv/cosmetic-1"),
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

    // ---- FFZ's list ------------------------------------------------------------------------------------

    @Test
    fun anFfzBadgeIsFoundForEveryoneOnItsList() = runTest {
        badges.loadThirdParty()

        listOf("900", "77", "300").forEach { id ->
            val shown = badges.resolve(null, null, userId = id).single()
            assertEquals("FFZ Supporter", shown.title)
            assertEquals(BadgeProvider.Ffz, shown.provider)
            assertEquals("https://cdn.frankerfacez.com/badge/3/2", shown.url)
        }
        assertTrue(badges.resolve(null, null, userId = "78").isEmpty())
        assertTrue("not a Twitch id", badges.resolve(null, null, userId = "abc").isEmpty())
    }

    @Test
    fun anFfzBadgeKeepsItsColourBehindThePicture() = runTest {
        badges.loadThirdParty()
        assertEquals(0xFF755000.toInt(), badges.resolve(null, null, userId = "77").single().background)
    }

    @Test
    fun anFfzBadgeFromAnotherHostIsLeftOut() = runTest {
        badges.loadThirdParty()
        assertEquals(listOf("supporter"), badges.resolve(null, null, userId = "77").map { it.set })
    }

    @Test
    fun ffzBadgesGoWhenTurnedOff() = runTest {
        badges.loadThirdParty()
        badges.enabled = BadgeProvider.entries.toSet() - BadgeProvider.Ffz
        assertTrue(badges.resolve(null, null, userId = "77").isEmpty())
    }

    @Test
    fun ffzThatIsDownIsNamedAndTriedAgain() = runTest {
        val said = mutableListOf<String>()
        val watching = launch { trouble.unreachable.collect { said += it } }
        runCurrent()

        others.ffzSupporters = null
        badges.loadThirdParty()
        runCurrent()
        assertEquals(listOf(ServiceTrouble.FFZ), said)

        others.ffzSupporters = longArrayOf(77)
        clock += 6 * 60_000L
        badges.retryMissing()
        assertEquals(1, badges.resolve(null, null, userId = "77").size)

        clock += 6 * 60_000L
        badges.retryMissing()
        assertEquals("once it answered, it is not asked again", 2, others.ffzCalls)
        watching.cancel()
    }

    @Test
    fun onlyAPlainHexColourIsAColour() {
        assertEquals(0xFFFAAF19.toInt(), cssColor("#FAAF19"))
        assertEquals(0xFF595959.toInt(), cssColor("595959"))
        assertEquals(null, cssColor("red"))
        assertEquals(null, cssColor("#FFF"))
        assertEquals(null, cssColor(null))
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
    @Test
    fun removingAChannelDropsOnlyItsBadges() = runTest {
        badges.loadGlobal()
        badges.loadChannel("a")
        badges.loadChannel("b")
        badges.removeChannel("a")
        assertEquals("Subscriber", badges.resolve("a", "subscriber/0", null).single().title)
        assertEquals("Subscriber of this channel", badges.resolve("b", "subscriber/0", null).single().title)
        assertTrue(!badges.complete("a"))
        badges.loadChannel("a")
        assertEquals("Subscriber of this channel", badges.resolve("a", "subscriber/0", null).single().title)
    }

    @Test
    fun aBadgeAnswerAfterRemovalCannotRestoreTheChannel() = runTest {
        val gate = CompletableDeferred<Unit>()
        twitch.channelGate = gate
        val pending = launch { badges.loadChannel("a") }
        runCurrent()
        badges.removeChannel("a")
        gate.complete(Unit)
        pending.join()
        assertTrue(badges.resolve("a", "subscriber/0", null).isEmpty())
        assertTrue(!badges.complete("a"))
        assertTrue(badges.arrived.value.isEmpty())
    }

    @Test
    fun removedFailedBadgesAreNotRetried() = runTest {
        badges.loadGlobal()
        badges.loadThirdParty()
        twitch.channel = null
        badges.loadChannel("a")
        badges.removeChannel("a")
        badges.retryMissing()
        assertEquals(1, twitch.channelCalls)
    }

    @Test
    fun cosmeticDefinitionsStayBoundedWithoutLosingCurrentWearers() {
        badges.sevenTvBadge("worn", "Worn", "Worn")
        badges.sevenTvWearer("42", "worn", worn = true)
        val paint = NamePaint("worn", NamePaint.Kind.Linear, emptyList())
        badges.sevenTvPaint(paint)
        badges.sevenTvPaintWearer("42", "worn", worn = true)
        repeat(6_000) { index ->
            badges.sevenTvBadge("badge-$index", "Badge", "Badge")
            badges.sevenTvPaint(paint.copy(id = "paint-$index"))
        }
        assertTrue(badges.knownKinds().size <= 5_512)
        assertEquals("worn", badges.resolve(null, null, "42").single().set)
        assertEquals(paint, badges.paint("42"))
        badges.sevenTvWearer("43", "badge-5999", worn = true)
        badges.sevenTvPaintWearer("43", "paint-5999", worn = true)
        assertEquals("badge-5999", badges.resolve(null, null, "43").single().set)
        assertEquals("paint-5999", badges.paint("43")?.id)
    }

}
