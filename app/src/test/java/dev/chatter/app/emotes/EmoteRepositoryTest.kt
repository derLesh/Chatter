package dev.chatter.app.emotes

import dev.chatter.app.net.BttvChannel
import dev.chatter.app.net.BttvEmote
import dev.chatter.app.net.FfzEmote
import dev.chatter.app.net.FfzGlobal
import dev.chatter.app.net.FfzRoom
import dev.chatter.app.net.FfzSet
import dev.chatter.app.net.HelixEmote
import dev.chatter.app.net.SevenTvActiveEmote
import dev.chatter.app.net.SevenTvEmoteData
import dev.chatter.app.net.SevenTvEmoteSet
import dev.chatter.app.net.SevenTvHost
import dev.chatter.app.net.SevenTvUser
import dev.chatter.app.net.SevenTvUserRef
import dev.chatter.app.net.ServiceTrouble
import dev.chatter.app.net.ThirdPartyEmoteApi
import dev.chatter.app.net.TwitchEmoteApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The emote repository against providers that answer whatever the test needs them to — including
 * not at all, which is the answer most of its rules are about.
 */
class EmoteRepositoryTest {
    private val providers = FakeProviders()
    private val twitch = FakeTwitch()
    private val trouble = ServiceTrouble()
    private var clock = 0L
    private val emotes = EmoteRepository(twitch, providers, trouble) { clock }

    private val channel = "22484632"

    // ---- the fakes --------------------------------------------------------------------------

    private class FakeProviders : ThirdPartyEmoteApi {
        /** Emote names per provider; null makes that provider unreachable. */
        var ffz: List<String>? = emptyList()
        var bttv: List<String>? = emptyList()
        var sevenTv: List<String>? = emptyList()
        var calls = 0

        private fun <T> answer(names: List<String>?, build: (List<String>) -> T): T {
            calls++
            return build(names ?: throw IOException("provider is down"))
        }

        override suspend fun bttvGlobal() = answer(bttv) { it.toBttv() }
        override suspend fun bttvChannel(channelId: String) =
            answer(bttv) { BttvChannel(channelEmotes = it.toBttv()) }

        override suspend fun ffzGlobal() =
            answer(ffz) { FfzGlobal(defaultSets = listOf(1), sets = mapOf("1" to FfzSet(it.toFfz()))) }

        override suspend fun ffzChannel(channelId: String) = answer(ffz) { FfzRoom(mapOf("1" to FfzSet(it.toFfz()))) }

        override suspend fun sevenTvGlobal() = answer(sevenTv) { SevenTvEmoteSet("global", it.toSevenTv()) }

        override suspend fun sevenTvChannel(channelId: String) = answer(sevenTv) {
            SevenTvUser(SevenTvEmoteSet("set-$channelId", it.toSevenTv()), SevenTvUserRef("user-$channelId"))
        }

        private fun List<String>.toBttv() = map { BttvEmote(id = "bttv-$it", code = it) }
        private fun List<String>.toFfz() = mapIndexed { i, name ->
            FfzEmote(id = i.toLong(), name = name, urls = mapOf("2" to "https://ffz.invalid/$name"))
        }

        private fun List<String>.toSevenTv() = map {
            SevenTvActiveEmote(
                id = "stv-$it",
                name = it,
                data = SevenTvEmoteData(host = SevenTvHost("//cdn.7tv.invalid/emote/stv-$it")),
            )
        }
    }

    private class FakeTwitch : TwitchEmoteApi {
        var own: List<String> = emptyList()
        var follower: List<String> = emptyList()
        var follows = true

        override suspend fun userEmotes(userId: String) =
            own.map { HelixEmote(id = "twitch-$it", name = it, emoteType = "subscriptions") }

        override suspend fun channelEmotes(channelId: String) =
            follower.map { HelixEmote(id = "follower-$it", name = it, emoteType = "follower") }

        override suspend fun isFollowing(userId: String, channelId: String) = follows
    }

    // ---- what a name means ------------------------------------------------------------------

    @Test
    fun ffzBeatsBttvBeatsSevenTv() = runTest {
        providers.ffz = listOf("susge")
        providers.bttv = listOf("susge", "catJAM")
        providers.sevenTv = listOf("susge", "catJAM", "peepoHappy")
        emotes.loadChannel(channel, userId = null)

        assertEquals(EmoteProvider.Ffz, emotes.lookup(channel, "susge")?.provider)
        assertEquals(EmoteProvider.Bttv, emotes.lookup(channel, "catJAM")?.provider)
        assertEquals(EmoteProvider.SevenTv, emotes.lookup(channel, "peepoHappy")?.provider)
    }

    @Test
    fun aChannelEmoteBeatsAGlobalOne() = runTest {
        providers.sevenTv = listOf("catJAM")
        emotes.loadGlobal()
        providers.sevenTv = emptyList()
        providers.bttv = listOf("catJAM")
        emotes.loadChannel(channel, userId = null)

        assertEquals(EmoteProvider.Bttv, emotes.lookup(channel, "catJAM")?.provider)
        assertEquals("elsewhere the global one still stands", EmoteProvider.SevenTv, emotes.lookup(null, "catJAM")?.provider)
    }

    // ---- a provider that does not answer ------------------------------------------------------

    @Test
    fun aProviderThatIsDownKeepsTheEmotesItHadLastTime() = runTest {
        providers.bttv = listOf("susge")
        providers.sevenTv = listOf("catJAM")
        emotes.loadChannel(channel, userId = null)

        providers.bttv = null
        providers.sevenTv = listOf("catJAM", "peepoHappy")
        emotes.loadChannel(channel, userId = null)

        assertEquals("BTTV said nothing at all", EmoteProvider.Bttv, emotes.lookup(channel, "susge")?.provider)
        assertEquals(EmoteProvider.SevenTv, emotes.lookup(channel, "peepoHappy")?.provider)
    }

    @Test
    fun aProviderThatAnswersWithNothingLosesItsEmotes() = runTest {
        providers.bttv = listOf("susge")
        emotes.loadChannel(channel, userId = null)
        providers.bttv = emptyList()
        emotes.loadChannel(channel, userId = null)

        assertNull("the channel really has no BTTV emotes any more", emotes.lookup(channel, "susge"))
    }

    @Test
    fun aProviderThatIsUnreachableIsNamedOnce() = runTest {
        val said = mutableListOf<String>()
        val watching = launch { trouble.unreachable.collect { said += it } }
        runCurrent()

        providers.ffz = null
        providers.sevenTv = null
        emotes.loadChannel(channel, userId = null)
        emotes.loadChannel("other", userId = null)
        runCurrent()

        assertEquals("a second channel runs into the same outage and says nothing about it",
            setOf(EmoteProvider.Ffz.label, EmoteProvider.SevenTv.label), said.toSet())
        watching.cancel()
    }

    // ---- coming back to a provider that was away -----------------------------------------------

    @Test
    fun onlyTheProviderThatWasSilentIsAskedAgain() = runTest {
        providers.ffz = null
        providers.bttv = listOf("susge")
        emotes.loadChannel(channel, userId = null)
        val afterFirst = providers.calls

        providers.ffz = listOf("pepeD")
        assertTrue(emotes.retryMissing(userId = null))
        assertEquals("BTTV and 7TV answered and are left alone", afterFirst + 1, providers.calls)
        assertEquals(EmoteProvider.Ffz, emotes.lookup(channel, "pepeD")?.provider)
        assertEquals("what was already there stays", EmoteProvider.Bttv, emotes.lookup(channel, "susge")?.provider)
    }

    @Test
    fun nothingIsAskedAgainWhileEveryProviderHasAnswered() = runTest {
        emotes.loadGlobal()
        emotes.loadChannel(channel, userId = null)
        val afterFirst = providers.calls

        assertFalse(emotes.waitingForProvider.value)
        assertFalse(emotes.retryMissing(userId = null))
        assertEquals(afterFirst, providers.calls)
    }

    @Test
    fun theAppStopsAskingOnceTheProviderIsBack() = runTest {
        providers.sevenTv = null
        emotes.loadChannel(channel, userId = null)
        assertTrue(emotes.waitingForProvider.value)

        providers.sevenTv = listOf("catJAM")
        emotes.retryMissing(userId = null)
        assertFalse(emotes.waitingForProvider.value)
    }

    @Test
    fun theChatIsToldToBuildItsMessagesAgainWhenEmotesArriveLate() = runTest {
        val rebuilds = mutableListOf<Unit>()
        val watching = launch { emotes.recovered.collect { rebuilds += it } }
        runCurrent()

        providers.ffz = null
        emotes.loadChannel(channel, userId = null)
        runCurrent()
        assertEquals("nothing to build again while it is still missing", 0, rebuilds.size)

        providers.ffz = listOf("pepeD")
        emotes.retryMissing(userId = null)
        runCurrent()
        assertEquals(1, rebuilds.size)
        watching.cancel()
    }

    @Test
    fun aMessageIsOnlyKeptRebuildableWhileAProviderOwesEmotes() = runTest {
        providers.ffz = null
        emotes.loadChannel(channel, userId = null)
        assertFalse(emotes.complete(channel))
        assertFalse("nor have the global emotes been asked for yet", emotes.complete(null))

        providers.ffz = emptyList()
        emotes.retryMissing(userId = null)
        emotes.loadGlobal()
        assertTrue(emotes.complete(channel))
    }

    // ---- when it is worth asking again --------------------------------------------------------

    @Test
    fun aChannelThatJustLoadedIsNotAskedAgain() = runTest {
        emotes.loadChannel(channel, userId = null)
        val afterFirst = providers.calls

        emotes.refreshChannel(channel)
        assertEquals("nothing can have changed in a minute", afterFirst, providers.calls)

        clock += 16 * 60_000L
        emotes.refreshChannel(channel)
        assertTrue(providers.calls > afterFirst)
    }

    @Test
    fun aChannelWhoseLoadFailedIsAskedAgainAtOnce() = runTest {
        providers.sevenTv = null
        emotes.loadChannel(channel, userId = null)
        val afterFirst = providers.calls

        providers.sevenTv = listOf("catJAM")
        emotes.refreshChannel(channel)
        assertTrue("a failed load leaves no mark", providers.calls > afterFirst)
        assertEquals(EmoteProvider.SevenTv, emotes.lookup(channel, "catJAM")?.provider)
    }

    @Test
    fun theGlobalEmotesAreOnlyFetchedOnceTheyAreAllThere() = runTest {
        providers.sevenTv = null
        emotes.loadGlobal()
        val afterFirst = providers.calls

        providers.sevenTv = listOf("catJAM")
        emotes.loadGlobal()
        assertTrue("one of them had not answered", providers.calls > afterFirst)
        val afterSecond = providers.calls

        emotes.loadGlobal()
        assertEquals("now they are all in", afterSecond, providers.calls)
    }

    // ---- what the picker and the autocomplete are handed ---------------------------------------

    @Test
    fun twitchEmotesWinANameFromTheChannel() = runTest {
        twitch.own = listOf("Kappa")
        providers.sevenTv = listOf("Kappa", "catJAM")
        emotes.loadTwitchUserEmotes("me")
        emotes.loadChannel(channel, userId = null)

        val available = emotes.available(channel).associateBy { it.name }
        assertEquals(EmoteProvider.Twitch, available["Kappa"]?.provider)
        assertEquals(EmoteProvider.SevenTv, available["catJAM"]?.provider)
    }

    @Test
    fun followerEmotesOnlyComeAlongForSomebodyWhoFollows() = runTest {
        twitch.follower = listOf("channelSmile")
        twitch.follows = false
        emotes.loadChannel(channel, userId = "me")
        assertNull(emotes.lookupOwnTwitch(channel, "channelSmile"))

        twitch.follows = true
        emotes.loadChannel(channel, userId = "me")
        assertEquals(EmoteProvider.Twitch, emotes.lookupOwnTwitch(channel, "channelSmile")?.provider)
    }

    // ---- what the 7TV EventAPI pushes ----------------------------------------------------------

    @Test
    fun aPushedSevenTvEmoteDoesNotTakeANameBttvOwns() = runTest {
        providers.bttv = listOf("susge")
        providers.sevenTv = emptyList()
        emotes.loadChannel(channel, userId = null)

        emotes.applySevenTvUpdate(
            channel,
            SevenTvEvent.EmoteSetUpdate(
                setId = "set-$channel",
                actor = "someone",
                added = listOf(sevenTvEmote("susge"), sevenTvEmote("peepoHappy")),
                removed = emptyList(),
                renamed = emptyList(),
            ),
        )

        assertEquals(EmoteProvider.Bttv, emotes.lookup(channel, "susge")?.provider)
        assertEquals(EmoteProvider.SevenTv, emotes.lookup(channel, "peepoHappy")?.provider)
    }

    @Test
    fun theChannelIsSubscribedToWithTheSetAndTheUserItReported() = runTest {
        emotes.loadChannel(channel, userId = null)
        assertEquals(
            setOf(
                SevenTvSubscription.ofObject("emote_set.update", "set-$channel"),
                SevenTvSubscription.ofObject("user.update", "user-$channel"),
            ),
            emotes.sevenTvSubscriptions(),
        )
        assertEquals(channel, emotes.channelForSevenTvSet("set-$channel"))
        assertEquals(channel, emotes.channelForSevenTvUser("user-$channel"))
    }

    private fun sevenTvEmote(name: String) = SevenTvActiveEmote(
        id = "stv-$name",
        name = name,
        data = SevenTvEmoteData(host = SevenTvHost("//cdn.7tv.invalid/emote/stv-$name")),
    )
}
