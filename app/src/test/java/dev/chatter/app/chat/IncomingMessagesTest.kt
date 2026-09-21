package dev.chatter.app.chat

import dev.chatter.app.emotes.Emote
import dev.chatter.app.irc.IrcMessage
import dev.chatter.app.settings.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app makes of what Twitch says — a ban, a deleted message, a mention, the first
 * ROOMSTATE of a channel — with nothing of Twitch present but the lines themselves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IncomingMessagesTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val settings = MutableStateFlow(Settings(messageLimit = 50))

    private val buffers = MessageBuffers(scope, dispatcher, settings)
    private val rooms = Rooms()
    private val windows = ChatWindows()
    private val chatters = ChatterRegistry()

    private val emotes = object : EmoteSource {
        override fun lookup(channelId: String?, word: String): Emote? = null
        override fun lookupOwnTwitch(channelId: String?, word: String): Emote? = null
    }
    private val builder = MessageBuilder(emotes, { _, _, _ -> emptyList() }, chatters)

    private val notices = object : ChatNotices {
        override fun chatCleared() = "Chat cleared."
        override fun timeout(name: String, seconds: Int) = "$name timed out for $seconds seconds."
        override fun ban(name: String) = "$name banned."
    }

    private class CountedStats : ChatStats {
        var received = 0
        var mentions = 0
        var sent = 0
        override fun countReceived() { received++ }
        override fun countMention() { mentions++ }
        override fun countSent(channel: String) { sent++ }
    }

    private val stats = CountedStats()
    private var filters = ChatFilters(mentions = MentionMatcher("lukas", emptyList()))

    private val mentioned = mutableListOf<Pair<ChatItem, Boolean>>()
    private val whispered = mutableListOf<Pair<InboxWhisper, Boolean>>()
    private val roomsFound = mutableListOf<String>()

    private val incoming = IncomingMessages(
        builder = builder,
        buffers = buffers,
        rooms = rooms,
        windows = windows,
        chatters = chatters,
        notices = notices,
        stats = stats,
        filters = { filters },
        selfLogin = { "lukas" },
        onMention = { item, watched -> mentioned += item to watched },
        onWhisper = { whisper, watched -> whispered += whisper to watched },
        onRoomFound = { roomsFound += it },
    )

    private val window = Any()

    private fun line(raw: String) = IrcMessage.parse(raw)!!

    private fun privmsg(from: String, text: String, channel: String = "forsen", id: String = text.hashCode().toString()) =
        line("@id=$id;display-name=$from :$from!$from@$from.tmi.twitch.tv PRIVMSG #$channel :$text")

    // ---- messages ------------------------------------------------------------------------------

    @Test
    fun aMessageLandsInItsChannelAndIsCounted() = runTest(dispatcher) {
        buffers.open("forsen")
        incoming.handle(privmsg("someone", "hello"))
        assertEquals(1, stats.received)
        assertEquals(listOf("someone"), chatters.names("forsen"))
    }

    @Test
    fun aMutedWordNeverReachesTheChannel() = runTest(dispatcher) {
        buffers.open("forsen")
        filters = filters.copy(muted = MuteFilter(keywords = listOf("spoiler")))
        incoming.handle(privmsg("someone", "spoiler ahead"))
        incoming.handle(privmsg("someone", "nothing to see"))

        val watcher = launch { buffers.messages("forsen").collect {} }
        advanceUntilIdle()
        assertEquals(listOf("nothing to see"), buffers.messages("forsen").value.map { it.text })
        assertEquals("only what reaches the chat is counted", 1, stats.received)
        watcher.cancel()
    }

    @Test
    fun aMutedMessageStillMarksHowFarTheChannelHasBeenRead() = runTest(dispatcher) {
        buffers.open("forsen")
        filters = filters.copy(muted = MuteFilter(keywords = listOf("spoiler")))
        incoming.handle(
            line("@id=1;tmi-sent-ts=1700000000000 :someone!someone@someone.tmi.twitch.tv PRIVMSG #forsen :spoiler ahead")
        )
        // Otherwise the next reconnect would fetch it from the history service all over again.
        assertEquals(1700000000000L, incoming.lastLive("forsen"))
    }

    @Test
    fun aRuleThatHidesAMessageHidesIt() = runTest(dispatcher) {
        buffers.open("forsen")
        filters = filters.copy(
            rules = RuleEngine(listOf(ChatRule(id = "r1", pattern = "bet", action = RuleAction.Hide))),
        )
        incoming.handle(privmsg("someone", "bet"))
        incoming.handle(privmsg("someone", "hello"))

        val watcher = launch { buffers.messages("forsen").collect {} }
        advanceUntilIdle()
        assertEquals(listOf("hello"), buffers.messages("forsen").value.map { it.text })
        watcher.cancel()
    }

    // ---- mentions -------------------------------------------------------------------------------

    @Test
    fun aMentionInTheChannelOnScreenIsNotSomethingToRingAbout() = runTest(dispatcher) {
        buffers.open("forsen")
        windows.setVisible(window, visible = true, channel = "forsen")
        incoming.handle(privmsg("someone", "hey lukas"))

        assertEquals(1, mentioned.size)
        assertTrue("the user was looking at it", mentioned.single().second)
        assertEquals(1, stats.mentions)
    }

    @Test
    fun aMentionInAChannelNobodyIsLookingAtIsUnreadAndWorthRinging() = runTest(dispatcher) {
        buffers.open("forsen")
        windows.setVisible(window, visible = true, channel = "xqc")
        incoming.handle(privmsg("someone", "hey lukas"))

        assertFalse(mentioned.single().second)
        assertEquals(mapOf("forsen" to 1), unreadAfterPublish())
    }

    @Test
    fun theUsersOwnMessagesAreNeitherUnreadNorMentions() = runTest(dispatcher) {
        buffers.open("forsen")
        incoming.handle(privmsg("lukas", "lukas talking to himself"))
        assertEquals(emptyList<Pair<ChatItem, Boolean>>(), mentioned)
        assertEquals("nothing arrived from anybody else", 0, stats.received)
        assertEquals(emptyMap<String, Int>(), unreadAfterPublish())
    }

    private fun TestScope.unreadAfterPublish(): Map<String, Int> {
        val watcher = launch { buffers.messages("forsen").collect {} }
        advanceUntilIdle()
        watcher.cancel()
        return buffers.unreadMessages.value
    }

    // ---- moderation ------------------------------------------------------------------------------

    @Test
    fun aBanStrikesThroughEverythingThatChatterSaidAndSaysSo() = runTest(dispatcher) {
        buffers.open("forsen")
        incoming.handle(privmsg("rulebreaker", "one"))
        incoming.handle(privmsg("rulebreaker", "two"))
        incoming.handle(privmsg("someone", "three"))
        incoming.handle(line(":tmi.twitch.tv CLEARCHAT #forsen :rulebreaker"))

        val watcher = launch { buffers.messages("forsen").collect {} }
        advanceUntilIdle()
        val shown = buffers.messages("forsen").value
        assertEquals(listOf(true, true, false, false), shown.map { it.deleted })
        assertEquals("rulebreaker banned.", shown.last().systemText)
        watcher.cancel()
    }

    @Test
    fun aTimeoutSaysHowLong() = runTest(dispatcher) {
        buffers.open("forsen")
        incoming.handle(line("@ban-duration=600 :tmi.twitch.tv CLEARCHAT #forsen :rulebreaker"))

        val watcher = launch { buffers.messages("forsen").collect {} }
        advanceUntilIdle()
        assertEquals("rulebreaker timed out for 600 seconds.", buffers.messages("forsen").value.single().systemText)
        watcher.cancel()
    }

    @Test
    fun aClearedChatKeepsItsMessagesAndSaysWhatHappened() = runTest(dispatcher) {
        buffers.open("forsen")
        incoming.handle(privmsg("someone", "hello"))
        incoming.handle(line(":tmi.twitch.tv CLEARCHAT #forsen"))

        val watcher = launch { buffers.messages("forsen").collect {} }
        advanceUntilIdle()
        val shown = buffers.messages("forsen").value
        assertEquals(listOf("hello", "Chat cleared."), shown.map { it.text })
        assertFalse("clearing is not deleting, the user can still read back", shown.first().deleted)
        watcher.cancel()
    }

    @Test
    fun aDeletedMessageIsStruckThroughByItsId() = runTest(dispatcher) {
        buffers.open("forsen")
        incoming.handle(privmsg("someone", "oops", id = "msg-1"))
        incoming.handle(privmsg("someone", "fine", id = "msg-2"))
        incoming.handle(line("@target-msg-id=msg-1 :tmi.twitch.tv CLEARMSG #forsen :oops"))

        val watcher = launch { buffers.messages("forsen").collect {} }
        advanceUntilIdle()
        assertEquals(listOf(true, false), buffers.messages("forsen").value.map { it.deleted })
        watcher.cancel()
    }

    // ---- what Twitch says about the room -----------------------------------------------------------

    @Test
    fun theFirstRoomStateOfAChannelIsWhenItsEmotesCanBeFetched() = runTest(dispatcher) {
        incoming.handle(line("@room-id=22484632;slow=30 :tmi.twitch.tv ROOMSTATE #forsen"))
        assertEquals(listOf("22484632"), roomsFound)
        assertEquals(30, rooms.states.value["forsen"]?.slow)

        incoming.handle(line("@room-id=22484632;slow=0 :tmi.twitch.tv ROOMSTATE #forsen"))
        assertEquals("the id was known, so nothing is fetched twice", listOf("22484632"), roomsFound)
        assertEquals(0, rooms.states.value["forsen"]?.slow)
    }

    @Test
    fun theBadgesInAUserStateSayWhatTheUserMayDoHere() = runTest(dispatcher) {
        incoming.handle(line("@badges=moderator/1 :tmi.twitch.tv USERSTATE #forsen"))
        assertEquals(ChatRole.Moderator, rooms.roles.value["forsen"])
        assertTrue(rooms.isPrivileged("forsen"))
    }

    // ---- whispers ------------------------------------------------------------------------------------

    @Test
    fun aWhisperIsReadAlreadyWhenItsTabIsInFront() = runTest(dispatcher) {
        windows.setVisible(window, visible = true, channel = null)
        windows.whispersVisible.value = true
        incoming.handle(line("@display-name=Someone;user-id=7 :someone!someone@someone.tmi.twitch.tv WHISPER lukas :hello"))
        assertTrue(whispered.single().second)

        windows.whispersVisible.value = false
        incoming.handle(line("@display-name=Someone;user-id=7 :someone!someone@someone.tmi.twitch.tv WHISPER lukas :again"))
        assertFalse(whispered.last().second)
    }

    @Test
    fun aMutedWhisperNeverArrives() = runTest(dispatcher) {
        filters = filters.copy(muted = MuteFilter(blocked = setOf("someone")))
        incoming.handle(line("@display-name=Someone;user-id=7 :someone!someone@someone.tmi.twitch.tv WHISPER lukas :hello"))
        assertEquals(emptyList<Pair<InboxWhisper, Boolean>>(), whispered)
    }
}
