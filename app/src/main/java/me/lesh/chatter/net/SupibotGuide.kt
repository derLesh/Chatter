package me.lesh.chatter.net

import me.lesh.chatter.chat.BotCommand

/**
 * How to write Supibot's commands, by hand from its help pages on supinic.com (as of November
 * 2026): what follows each name, and what an alias does differently. The help has examples for
 * two thirds of the commands only, mixes them with real values, and says what an alias does only
 * in its text. A command missing here, like one Supibot adds later, is read from its help instead.
 *
 * A usage is written the way [me.lesh.chatter.chat.BotUsage.forms] reads it: "<x>" must be given,
 * "[x]" may be, "<a|b>" is one of these words, a bare word is typed as it is, and " | " splits the
 * ways of writing it. Named parameters like "alerts:true" are not here; Supibot's help lists them.
 */
internal object SupibotGuide {
    /** [aliases] are those that differ from the command; any other alias takes [usage]. */
    class Command(val usage: String, val aliases: Map<String, Alias> = emptyMap())

    /** [note] says what the alias does differently; a null [usage] is the command's. */
    class Alias(val note: String?, val usage: String? = null)

    /** [command] with the usage written here, if there is one for it. */
    fun applyTo(command: BotCommand): BotCommand {
        val guide = COMMANDS[command.name] ?: return command
        return command.copy(
            usage = guide.usage,
            aliasUsages = guide.aliases.mapNotNull { (alias, it) -> it.usage?.let { usage -> alias to usage } }.toMap(),
            aliasNotes = guide.aliases.mapNotNull { (alias, it) -> it.note?.let { note -> alias to note } }.toMap(),
        )
    }

    internal fun usages(): List<String> = COMMANDS.values.flatMap { listOf(it.usage) + it.aliases.values.mapNotNull { a -> a.usage } }

    private fun command(usage: String, vararg aliases: Pair<String, Alias>) = Command(usage, mapOf(*aliases))

    private fun alias(note: String?, usage: String? = null) = Alias(note, usage)

    private val COMMANDS: Map<String, Command> = mapOf(
        "8ball" to command("<question>"),
        "accountage" to command("[user]"),
        "addbetween" to command("<word> <message>"),
        "afk" to command(
            "[status]",
            "gn" to alias("Goes AFK to sleep"),
            "brb" to alias("Goes AFK for a moment"),
            "shower" to alias("Goes AFK to shower"),
            "food" to alias("Goes AFK to eat"),
            "lurk" to alias("Goes AFK to lurk"),
            "poop" to alias("Goes AFK to the toilet"),
            "work" to alias("Goes AFK to work"),
            "study" to alias("Goes AFK to study"),
            "nap" to alias("Goes AFK for a nap"),
        ),
        "alias" to command(
            "add <name> <definition> | edit <name> <definition> | addedit <name> <definition> | run <name> [arguments] | " +
                "remove <name> | rename <old-name> <new-name> | duplicate <old-name> <new-name> | " +
                "describe <alias> <description> | check [user] [alias] | inspect <alias> | list | " +
                "copy <user> <alias> | link <user> <alias> [custom name] | try <user> <alias> [arguments] | " +
                "publish <alias> <user> | unpublish <alias> | published | restrict <name> <type> | " +
                "unrestrict <name> <type> | transfer <previous name>",
            "\$" to alias("Runs one of your aliases", "<alias> [arguments]"),
        ),
        "aliasbuildingblock" to command("<type>"),
        "ban" to command("", "unban" to alias("Lifts a ban")),
        "block" to command("<user> <command> | <user> all", "unblock" to alias("Lifts a block")),
        "bot" to command(
            "check-live | enable-links | disable-links | enable-global-emotes | disable-global-emotes | " +
                "offline-only | disable-offline-only | enable-rustlog | enable | disable | rejoin | rename | api",
        ),
        "chan" to command(
            "<board> [query]",
            "textchan" to alias("Only posts without a file"),
            "filechan" to alias("Only posts with a file"),
            "imagechan" to alias("Only posts with an image"),
        ),
        "channelfounderlist" to command("[channel]"),
        "chatsummary" to command("[channel]"),
        "check" to command(
            "afk <user> | ambassadors [channel] | changelog [ID] | cookie [user] | gpt [user] | gpt total | deepl | " +
                "error <ID> | location [user] | logs | mariadb | reminder <ID> | reminder last | slots | " +
                "subscriptions | suggestion <ID>",
        ),
        "cookie" to command("eat | donate <user> | stats [user] | top"),
        "countline" to command("[user]"),
        "crypto" to command("[symbol]"),
        "cryptogame" to command(
            "register | buy <amount> <asset> | sell <amount> <asset> | check [user] | total [user] | rank [user] | " +
                "average | assets | prices | portfolios | leaderboard",
        ),
        "currency" to command("<amount> <currency> to <currency> | <currency> to <currency>"),
        "dalle" to command("list | <prompt>"),
        "dayoftheyear" to command(
            "[date]",
            "monthoftheyear" to alias("For the month"),
            "moty" to alias("For the month"),
        ),
        "define" to command("<term>"),
        "dictionary" to command("<word>"),
        "doesnotexist" to command("[anime|automobile|fuckeduphomer|fursona|mp|person|waifu|wojak|word]"),
        "downloadclip" to command("<clip link>"),
        "espn" to command(
            "",
            "nba" to alias("NBA games"),
            "nfl" to alias("NFL games"),
            "nhl" to alias("NHL games"),
            "mlb" to alias("MLB games"),
        ),
        "fill" to command("<message>"),
        "firstchannelfollower" to command("[user]"),
        "firstfollowedchannel" to command("[user]"),
        "firstline" to command("[user]"),
        "firstseen" to command("[user]"),
        "fish" to command(
            "[bait] | buy | sell <item> [amount] | sell all <fish|junk> | sell duplicate <fish|junk> | " +
                "show [fish|junk] | show <user> [fish|junk] | stats [user] | top [type] | trap [cancel|reset] | config whisper <on|off> | " +
                "config discord-reactions <type>",
        ),
        "followage" to command("[user] [channel]"),
        "formula1" to command("wdc | wcc | driverStandings | constructorStandings | race <name> | ferrari [driver|race|link] | copypasta | gimi"),
        "gachicheck" to command("<link>"),
        "gachisearch" to command(
            "<track>",
            "gsa" to alias("Searches by author", "<author>"),
            "gachiauthorsearch" to alias("Searches by author", "<author>"),
        ),
        "getprofilepicture" to command("[user]"),
        "gpt" to command("<question>"),
        "help" to command(
            "[command]",
            "commands" to alias("Lists all commands", ""),
            "helpgrep" to alias("Searches the names and descriptions of all commands", "<text>"),
        ),
        "horoscope" to command("[zodiac sign]"),
        "howlongtobeat" to command("<game>"),
        "id" to command("[user]"),
        "isdown" to command("<website>"),
        "kiss" to command("<user>"),
        "knowyourmeme" to command("<meme>"),
        "lastline" to command("[user]"),
        "lastseen" to command("<user>"),
        "math" to command("<expression>"),
        "moba" to command(
            "",
            "league" to alias(
                "League of Legends",
                "[region] [summoner] | last [region] [summoner] | live [region] [summoner] | rank [region] [summoner]",
            ),
            "dota" to alias("Dota 2"),
        ),
        "news" to command("[text] | <country code> [text]"),
        "nutrients" to command("<food>"),
        "ocr" to command("<image link>"),
        "optout" to command("<command> | all", "unoptout" to alias("Undoes an opt-out")),
        "origin" to command("<emote>"),
        "osrs" to command(
            "<player> | stats <player> | kc <activity> | kc @<player> <activity> | price <item> | itemid <item> | search <query> | " +
                "playercount | stars | status | tog | tears | guthix",
        ),
        "pastebin" to command(
            "get <link> | post <text>",
            "pbg" to alias("Gets a Pastebin paste", "<link>"),
            "pbp" to alias("Puts your text on Pastebin", "<text>"),
            "hbg" to alias("Gets a Hastebin paste", "<link>"),
            "hbp" to alias("Puts your text on Hastebin", "<text>"),
            "gist" to alias("Gets a GitHub gist", "<gist ID>"),
        ),
        "pick" to command("<words>"),
        "pingme" to command("<user> [text]"),
        "pipe" to command("<commands, split by |>"),
        "poe" to command("", "poe2" to alias("For Path of Exile 2")),
        "polymarket" to command("<question>"),
        "query" to command("<question>"),
        "randomanimalfact" to command(
            "<bird|cat|dog|fox>",
            "rbf" to alias("About birds", ""),
            "rcf" to alias("About cats", ""),
            "rdf" to alias("About dogs", ""),
            "rff" to alias("About foxes", ""),
        ),
        "randomanimalpicture" to command(
            "<bird|cat|dog|fox>",
            "rbp" to alias("Of a bird", ""),
            "rcp" to alias("Of a cat", ""),
            "rdp" to alias("Of a dog", ""),
            "rfp" to alias("Of a fox", ""),
        ),
        "randomclip" to command("[channel]"),
        "randomcocktail" to command("[name]"),
        "randomemoji" to command("[number]"),
        "randomemote" to command("[number]"),
        "randomhistoricevent" to command("[date]"),
        "randominstagram" to command("<instagram account>"),
        "randomline" to command("[user]", "rq" to alias("Only from your own lines", "")),
        "randommeal" to command("[name]"),
        "randommeme" to command("[subreddit]"),
        "randompastebin" to command("[language] | list"),
        "randomword" to command("[number]"),
        "remind" to command(
            "<user> <message>",
            "remindme" to alias("Reminds yourself", "<message>"),
            "remindprivate" to alias("Reminds by whisper"),
            "privateremind" to alias("Reminds by whisper"),
        ),
        "roll" to command("[min] [max] | <dice like 2d6>"),
        "russianarmylosses" to command("[category]"),
        "russianroulette" to command("[seconds]"),
        "set" to command(
            "birthday <MM-DD> | location <place> | language <language> | default-gpt-model <model> | " +
                "league-user <summoner> | league-region <region> | osrs-username <player> | trackfavourite <track> | " +
                "discord <text> | ambassador <user> | stalk-prevention | no-abb-chatter",
            "unset" to alias(
                "Removes a setting",
                "reminder <ID> | reminder last | suggestion <ID> | location | birthday | language | default-gpt-model | " +
                    "league-user | league-region | osrs-username | gc <link> | discord | ambassador <user>",
            ),
        ),
        "shoutout" to command("<channel>"),
        "shuffle" to command("<message>"),
        "slots" to command("<words> | leaderboard | winners"),
        "sort" to command("<message>"),
        "speedrun" to command("<game>"),
        "stalk" to command("<user>"),
        "statistics" to command(
            "afk [user] | total-afk [user] | longest-afk [user] | cookies [user] | totalcookiecount | aliases | " +
                "alias <user> | reminders [user] | gpt | discord | top-chatters | active-chatters",
        ),
        "steamgameplayers" to command("<game>"),
        "stock" to command("<symbol>"),
        "streaminfo" to command("[channel]"),
        "subage" to command("[user] [channel]"),
        "subscribe" to command("<event>", "unsubscribe" to alias("Ends a subscription")),
        "suggest" to command("<text>"),
        "texttransform" to command(
            "<type> <text>",
            "reversetexttransform" to alias("Turns transformed text back"),
            "rtt" to alias("Turns transformed text back"),
        ),
        "tf2" to command("roll [class]"),
        "thesaurus" to command("<text>"),
        "time" to command("[place]"),
        "topstreams" to command("[game]"),
        "totalcountline" to command("[user]"),
        "translate" to command("<text>", "deepl" to alias("Translates with DeepL")),
        "transliterate" to command("<text>"),
        "tuck" to command(
            "<user>",
            "hug" to alias("Hugs someone"),
            "headpat" to alias("Pats someone's head"),
            "gnkiss" to alias("Kisses someone good night"),
            "truck" to alias("Runs someone over with a truck"),
        ),
        "twitchlotto" to command("[channel]"),
        "twitter" to command("<account>"),
        "unmention" to command("<command> | all", "remention" to alias("Mentions you again")),
        "unping" to command("<command> | all", "reping" to alias("Pings you again")),
        "urban" to command("[term] | random"),
        "weather" to command("[place]"),
        "whatanimeisit" to command("<image link>"),
        "whatemoteisit" to command("<emote>"),
        "wiki" to command("<topic> | random"),
        "youtubesearch" to command("<query>"),
    )
}
