package dev.chatter.app.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chatter.app.badges.BadgeRepository
import dev.chatter.app.chat.ChatFilters
import dev.chatter.app.chat.ChatNotices
import dev.chatter.app.chat.ChatRule
import dev.chatter.app.chat.ChatStats
import dev.chatter.app.chat.ChatWindows
import dev.chatter.app.chat.ChatterRegistry
import dev.chatter.app.chat.IncomingMessages
import dev.chatter.app.chat.MentionMatcher
import dev.chatter.app.chat.MessageBuffers
import dev.chatter.app.chat.MessageBuilder
import dev.chatter.app.chat.MuteFilter
import dev.chatter.app.chat.Rooms
import dev.chatter.app.chat.RuleAction
import dev.chatter.app.chat.RuleEngine
import dev.chatter.app.chat.RuleTarget
import dev.chatter.app.emotes.EmoteRepository
import dev.chatter.app.irc.IrcMessage
import dev.chatter.app.net.BttvChannel
import dev.chatter.app.net.BttvEmote
import dev.chatter.app.net.ChatterSupporters
import dev.chatter.app.net.ChatterinoBadge
import dev.chatter.app.net.ChatterinoBadges
import dev.chatter.app.net.FfzEmote
import dev.chatter.app.net.FfzGlobal
import dev.chatter.app.net.FfzRoom
import dev.chatter.app.net.FfzSet
import dev.chatter.app.net.HelixBadgeSet
import dev.chatter.app.net.HelixBadgeVersion
import dev.chatter.app.net.HelixEmote
import dev.chatter.app.net.SevenTvActiveEmote
import dev.chatter.app.net.SevenTvEmoteData
import dev.chatter.app.net.SevenTvEmoteSet
import dev.chatter.app.net.SevenTvFile
import dev.chatter.app.net.SevenTvHost
import dev.chatter.app.net.SevenTvUser
import dev.chatter.app.net.ThirdPartyBadgeApi
import dev.chatter.app.net.ThirdPartyEmoteApi
import dev.chatter.app.net.TwitchBadgeApi
import dev.chatter.app.net.TwitchEmoteApi
import dev.chatter.app.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What one incoming message costs with no window open, which is what the battery pays all night
 * since Chatter stays joined in the background.
 *
 * Busy-channel lines ([BusyChannel]), the real emote and badge repositories filled through fake
 * providers, and a realistic mute list and rules. Nothing is published or drawn: this measures
 * parsing, building, filters, the chatter registry and the buffer.
 *
 * Each test reports time and allocations per message; garbage makes the collector run and keeps the
 * CPU awake. Run `./gradlew :app:connectedMicrobenchmarkAndroidTest` with a phone attached, or the
 * Benchmark workflow, which compares a branch with master.
 */
@RunWith(AndroidJUnit4::class)
class MessageCostBenchmark {
    @get:Rule
    val benchmark = BenchmarkRule()

    private val scope = CoroutineScope(Job())
    private val lines = BusyChannel.lines(LINES)
    private val parsed = lines.map { IrcMessage.parse(it)!! }
    private lateinit var incoming: IncomingMessages

    @Before
    fun setUp() = runBlocking {
        val emotes = EmoteRepository(NoTwitchEmotes, ThirdPartyEmotes)
        emotes.loadGlobal()
        emotes.loadChannel(BusyChannel.ROOM_ID, userId = null)
        val badges = BadgeRepository(TwitchBadges, OtherClientBadges)
        badges.loadGlobal()
        badges.loadChannel(BusyChannel.ROOM_ID)
        badges.loadThirdParty(supporterTitles = null)

        val chatters = ChatterRegistry()
        // The default limit. There are more lines than that, so the buffer trims as it would all
        // night.
        val settings = MutableStateFlow(Settings(messageLimit = 500))
        val buffers = MessageBuffers(scope, Dispatchers.Unconfined, settings).apply { open(BusyChannel.CHANNEL) }
        val filters = ChatFilters(
            mentions = MentionMatcher(BusyChannel.SELF, listOf("speedrun", "benchie")),
            muted = MuteFilter(
                keywords = listOf("spoiler", "ending", "boss", "giveaway", "discord", "prime", "follow4follow", "bits", "cheap", "viewers"),
                blocked = (0 until 400 step 13).map { "chatter_$it" }.toSet(),
            ),
            rules = RuleEngine(
                listOf(
                    ChatRule("1", "clip"),
                    ChatRule("2", "\\b(pb|world record|wr)\\b", regex = true),
                    ChatRule("3", "chatter_7", target = RuleTarget.Author, color = 0xFF2E8B57.toInt()),
                    ChatRule("4", "song", action = RuleAction.Hide),
                    ChatRule("5", "gg wp", action = RuleAction.Notify, channel = BusyChannel.CHANNEL),
                ),
            ),
        )
        incoming = IncomingMessages(
            builder = MessageBuilder(emotes, badges, chatters),
            buffers = buffers,
            rooms = Rooms(),
            windows = ChatWindows(),
            chatters = chatters,
            notices = Notices,
            stats = NoStats,
            filters = { filters },
            selfLogin = { BusyChannel.SELF },
            onMention = { _, _ -> },
            onWhisper = { _, _ -> },
            onRoomFound = {},
        )
        incoming.handle(IrcMessage.parse(BusyChannel.roomState)!!)
    }

    @After
    fun tearDown() = scope.cancel()

    /** Parsing a line into command, tags and text. */
    @Test
    fun parse() {
        var i = 0
        benchmark.measureRepeated {
            IrcMessage.parse(lines[i++ % LINES])
        }
    }

    /** Everything after parsing: building, filtering, buffering. */
    @Test
    fun handleParsed() {
        var i = 0
        benchmark.measureRepeated {
            incoming.handle(parsed[i++ % LINES])
        }
    }

    /** The whole way from the socket line to the buffer. */
    @Test
    fun parseAndHandle() {
        var i = 0
        benchmark.measureRepeated {
            incoming.handle(IrcMessage.parse(lines[i++ % LINES])!!)
        }
    }

    private companion object {
        const val LINES = 2_000
    }
}

// ---- the providers, answering with a busy channel's worth of emotes and badges -----------------

private object NoTwitchEmotes : TwitchEmoteApi {
    override suspend fun userEmotes(userId: String): List<HelixEmote> = emptyList()
    override suspend fun channelEmotes(channelId: String): List<HelixEmote> = emptyList()
    override suspend fun isFollowing(userId: String, channelId: String) = false
}

private object ThirdPartyEmotes : ThirdPartyEmoteApi {
    override suspend fun bttvGlobal() = listOf(BttvEmote("g1", "FeelsBirthdayMan"), BttvEmote("g2", "cvHazmat"))
    override suspend fun bttvChannel(channelId: String) =
        BttvChannel(channelEmotes = BusyChannel.bttvEmotes.mapIndexed { i, name -> BttvEmote("b$i", name) })
    override suspend fun ffzGlobal() = FfzGlobal()
    override suspend fun ffzChannel(channelId: String) = FfzRoom(
        sets = mapOf(
            "1" to FfzSet(
                BusyChannel.ffzEmotes.mapIndexed { i, name ->
                    FfzEmote(i.toLong(), name, urls = mapOf("1" to "//cdn.frankerfacez.com/emote/$i/1", "2" to "//cdn.frankerfacez.com/emote/$i/2"))
                },
            ),
        ),
    )
    override suspend fun sevenTvGlobal() = SevenTvEmoteSet(emotes = sevenTv(listOf("EZ", "Clap", "RainTime")))
    override suspend fun sevenTvChannel(channelId: String) =
        SevenTvUser(emoteSet = SevenTvEmoteSet(id = "set", emotes = sevenTv(BusyChannel.sevenTvEmotes)))

    private fun sevenTv(names: List<String>) = names.mapIndexed { i, name ->
        SevenTvActiveEmote(
            id = "7tv$i$name", name = name,
            data = SevenTvEmoteData(host = SevenTvHost("//cdn.7tv.app/emote/7tv$i", listOf(SevenTvFile("1x.webp", 32, 32)))),
        )
    }
}

private object TwitchBadges : TwitchBadgeApi {
    override suspend fun globalBadges() = listOf(
        set("moderator", "1"), set("vip", "1"), set("premium", "1"), set("sub-gifter", "5"), set("broadcaster", "1"),
    )
    override suspend fun channelBadges(channelId: String) = listOf(set("subscriber", "0", "3", "6", "12", "24", "36"))

    private fun set(id: String, vararg versions: String) = HelixBadgeSet(
        setId = id,
        versions = versions.map { HelixBadgeVersion(id = it, title = "$id $it", url2x = "https://static-cdn.jtvnw.net/badges/v1/$id$it/2") },
    )
}

private object OtherClientBadges : ThirdPartyBadgeApi {
    override suspend fun chatterinoBadges() = ChatterinoBadges(
        listOf(ChatterinoBadge(tooltip = "Chatterino Top Donator", image2 = "https://c.invalid/2", users = (0 until 50).map { (100_000 + it * 370).toString() })),
    )
    override suspend fun chatterSupporters() = ChatterSupporters()
}

private object Notices : ChatNotices {
    override fun chatCleared() = "Chat cleared."
    override fun timeout(name: String, seconds: Int) = "$name timed out for $seconds seconds."
    override fun ban(name: String) = "$name banned."
}

/** The real stats only add to counters in memory; not measured here. */
private object NoStats : ChatStats {
    override fun countReceived(channel: String) = Unit
    override fun countMention() = Unit
    override fun countSent(channel: String) = Unit
}
