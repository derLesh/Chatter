package dev.chatter.app.benchmark

import kotlin.random.Random

/**
 * A busy channel's lines as Twitch writes them: full tags, badges and colors, Twitch emotes with
 * positions, third-party emote names, replies with quoted messages, first messages, and occasional
 * subs, deletions and timeouts.
 *
 * Generated, not recorded: a recording would put other people's messages into the repository, and
 * comparing two branches needs identical input.
 */
object BusyChannel {
    const val CHANNEL = "benchchannel"
    const val ROOM_ID = "11148817"
    const val SELF = "benchuser"

    /** Third-party emotes used in the text; the benchmark's fakes serve these. */
    val sevenTvEmotes: List<String> = names("7tv", 600)
    val bttvEmotes: List<String> = names("bttv", 80)
    val ffzEmotes: List<String> = names("ffz", 40)

    private val twitchEmotes = listOf("25" to "Kappa", "88" to "PogChamp", "305954156" to "PogChamp2", "425618" to "LUL")
    private val words = (
        "the a to and is it that of you in this for on with was but not so what are just like " +
            "stream game play chat play gg wp nice clip go now yes no why how when who lol omg true " +
            "insane crazy boss run pb split reset again today tomorrow mods vod song music sound"
        ).split(' ')
    private val colors = listOf("#FF0000", "#1E90FF", "#9ACD32", "#FF69B4", "#DAA520", "#8A2BE2", "")

    /** The ROOMSTATE that opens the channel and gives its room id. */
    val roomState: String =
        "@emote-only=0;followers-only=-1;r9k=0;room-id=$ROOM_ID;slow=0;subs-only=0 :tmi.twitch.tv ROOMSTATE #$CHANNEL"

    /** [count] consecutive lines, identical for the same [seed]. */
    fun lines(count: Int, seed: Int = 7): List<String> {
        val random = Random(seed)
        val users = (0 until 400).map { "chatter_$it" to (100_000 + it * 37).toString() }
        val sentIds = ArrayDeque<String>()
        return List(count) { i ->
            val (login, userId) = users[(random.nextDouble() * random.nextDouble() * users.size).toInt()]
            val id = "0f${i.toString(16).padStart(6, '0')}-5a3b-4c2d-9e1f-${(i * 7919).toString(16).padStart(12, '0')}"
            val ts = 1_790_000_000_000L + i * 180L
            when {
                i % 97 == 0 && sentIds.isNotEmpty() ->
                    "@login=$login;room-id=;target-msg-id=${sentIds.random(random)};tmi-sent-ts=$ts :tmi.twitch.tv CLEARMSG #$CHANNEL :deleted"
                i % 211 == 0 ->
                    "@ban-duration=600;room-id=$ROOM_ID;target-user-id=$userId;tmi-sent-ts=$ts :tmi.twitch.tv CLEARCHAT #$CHANNEL :$login"
                i % 53 == 0 -> subscription(login, userId, id, ts, random)
                else -> {
                    sentIds.addLast(id)
                    if (sentIds.size > 200) sentIds.removeFirst()
                    privmsg(login, userId, id, ts, random, users)
                }
            }
        }
    }

    private fun privmsg(login: String, userId: String, id: String, ts: Long, random: Random, users: List<Pair<String, String>>): String {
        val parts = mutableListOf<String>()
        val twitch = mutableListOf<Pair<String, String>>()
        repeat(2 + random.nextInt(12)) {
            parts += when (random.nextInt(10)) {
                0, 1, 2 -> sevenTvEmotes[random.nextInt(sevenTvEmotes.size)]
                3 -> bttvEmotes[random.nextInt(bttvEmotes.size)]
                4 -> ffzEmotes[random.nextInt(ffzEmotes.size)]
                5 -> twitchEmotes[random.nextInt(twitchEmotes.size)].also { twitch += it }.second
                else -> words[random.nextInt(words.size)]
            }
        }
        // One in forty mentions the user, one in fifteen someone else.
        when {
            random.nextInt(40) == 0 -> parts.add(0, "@$SELF")
            random.nextInt(15) == 0 -> parts.add(0, "@${users[random.nextInt(users.size)].first}")
        }
        if (random.nextInt(60) == 0) parts += "https://clips.twitch.tv/SomeClipName-${random.nextInt(99999)}"
        val text = parts.joinToString(" ")
        val emotes = emotesTag(text, twitch)
        val tags = buildList {
            add("badge-info=subscriber/${1 + random.nextInt(40)}")
            add("badges=${badges(random)}")
            add("client-nonce=${random.nextLong().toULong().toString(16)}")
            add("color=${colors[random.nextInt(colors.size)]}")
            add("display-name=${login.replaceFirstChar { it.uppercase() }}")
            add("emotes=$emotes")
            add("first-msg=${if (random.nextInt(50) == 0) 1 else 0}")
            add("flags=")
            add("id=$id")
            add("mod=0")
            add("returning-chatter=0")
            add("room-id=$ROOM_ID")
            add("subscriber=1")
            add("tmi-sent-ts=$ts")
            add("turbo=0")
            add("user-id=$userId")
            add("user-type=")
            if (random.nextInt(12) == 0) {
                val (parent, parentId) = users[random.nextInt(users.size)]
                add("reply-parent-display-name=${parent.replaceFirstChar { it.uppercase() }}")
                add("reply-parent-msg-body=${escape(words.shuffled(random).take(8).joinToString(" "))}")
                add("reply-parent-msg-id=parent-${random.nextInt(100000)}")
                add("reply-parent-user-id=$parentId")
                add("reply-parent-user-login=$parent")
                add("reply-thread-parent-display-name=${parent.replaceFirstChar { it.uppercase() }}")
                add("reply-thread-parent-msg-id=thread-${random.nextInt(100000)}")
                add("reply-thread-parent-user-id=$parentId")
                add("reply-thread-parent-user-login=$parent")
            }
        }
        return "@${tags.joinToString(";")} :$login!$login@$login.tmi.twitch.tv PRIVMSG #$CHANNEL :$text"
    }

    private fun subscription(login: String, userId: String, id: String, ts: Long, random: Random): String {
        val months = 1 + random.nextInt(60)
        val tags = listOf(
            "badge-info=subscriber/$months", "badges=${badges(random)}", "color=#1E90FF",
            "display-name=${login.replaceFirstChar { it.uppercase() }}", "emotes=", "flags=", "id=$id",
            "login=$login", "mod=0", "msg-id=resub", "msg-param-cumulative-months=$months",
            "msg-param-months=0", "msg-param-should-share-streak=0", "msg-param-sub-plan-name=Channel\\sSubscription",
            "msg-param-sub-plan=1000", "msg-param-was-gifted=false", "room-id=$ROOM_ID", "subscriber=1",
            "system-msg=${login}\\ssubscribed\\sat\\sTier\\s1.\\sThey've\\ssubscribed\\sfor\\s$months\\smonths!",
            "tmi-sent-ts=$ts", "user-id=$userId", "user-type=",
        )
        return "@${tags.joinToString(";")} :tmi.twitch.tv USERNOTICE #$CHANNEL :${sevenTvEmotes[0]} $months months"
    }

    private fun badges(random: Random): String = buildList {
        when (random.nextInt(40)) {
            0 -> add("moderator/1")
            1 -> add("vip/1")
        }
        add("subscriber/${listOf(0, 3, 6, 12, 24, 36).random(random)}")
        if (random.nextInt(8) == 0) add("premium/1")
        if (random.nextInt(20) == 0) add("sub-gifter/5")
    }.joinToString(",")

    /** The `emotes` tag: each Twitch emote with its positions, in code points. */
    private fun emotesTag(text: String, used: List<Pair<String, String>>): String {
        if (used.isEmpty()) return ""
        val positions = LinkedHashMap<String, MutableList<String>>()
        var at = 0
        for (word in text.split(' ')) {
            twitchEmotes.firstOrNull { it.second == word }?.let { (id, _) ->
                positions.getOrPut(id) { mutableListOf() } += "$at-${at + word.length - 1}"
            }
            at += word.length + 1
        }
        return positions.entries.joinToString("/") { (id, spots) -> "$id:${spots.joinToString(",")}" }
    }

    private fun escape(text: String) = text.replace(" ", "\\s")

    private fun names(prefix: String, count: Int): List<String> {
        val random = Random(prefix.hashCode())
        val syllables = listOf("pog", "lul", "kek", "sad", "hap", "om", "ega", "cat", "dank", "mon", "pepe", "ge", "cry", "yep", "nop", "wid", "ers")
        return List(count) { i ->
            val name = (0 until 2 + random.nextInt(2)).joinToString("") { syllables[random.nextInt(syllables.size)] }
            name.replaceFirstChar { it.uppercase() } + if (i % 3 == 0) "" else i.toString()
        }.distinct()
    }
}
