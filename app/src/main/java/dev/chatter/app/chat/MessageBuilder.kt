package dev.chatter.app.chat

import dev.chatter.app.badges.Badge
import dev.chatter.app.badges.NamePaint
import dev.chatter.app.emotes.Cheermote
import dev.chatter.app.emotes.Emote
import dev.chatter.app.emotes.EmoteProvider
import dev.chatter.app.emotes.twitchEmoteUrl
import dev.chatter.app.irc.IrcMessage
import java.util.UUID

/** Where the builder gets emotes. Implemented by the repositories, faked in tests. */
interface EmoteSource {
    fun lookup(channelId: String?, word: String): Emote?
    /** Twitch emotes the user may use: their emote sets plus the channel's follower emotes. */
    fun lookupOwnTwitch(channelId: String?, word: String): Emote?

    /**
     * False while a provider still owes emotes here; a message built now may change once they
     * arrive, so it stays rebuildable. See [MessageBody].
     */
    fun complete(channelId: String?): Boolean = true
}

/** Where the builder finds cheermotes, by channel and prefix ("Cheer" of "Cheer100"). */
fun interface CheerSource {
    fun lookup(channelId: String?, prefix: String): Cheermote?

    companion object {
        val NONE = CheerSource { _, _ -> null }
    }
}

/** User settings that affect how emotes are recognized in new messages. */
data class EmoteOptions(
    val enabled: Boolean = true,
    val zeroWidth: Boolean = true,
    val showUnlisted: Boolean = false,
    /** Emotes from other providers stay plain text. */
    val providers: Set<EmoteProvider> = EmoteProvider.entries.toSet(),
)

fun interface BadgeSource {
    /**
     * [userId] from the `user-id` tag: other clients' badges belong to the user, not to the tag.
     */
    fun resolve(channelId: String?, badgesTag: String?, userId: String?): List<Badge>

    /** The 7TV paint [userId] wears, if any. */
    fun paint(userId: String?): NamePaint? = null
}

/** Turns IRC messages into [ChatItem]s, once per message. */
class MessageBuilder(
    private val emotes: EmoteSource,
    private val badges: BadgeSource,
    private val chatters: ChatterRegistry = ChatterRegistry(),
    private val cheers: CheerSource = CheerSource.NONE,
    private val options: () -> EmoteOptions = { EmoteOptions() },
) {
    fun build(
        msg: IrcMessage,
        selfLogin: String,
        channelId: String?,
        mentions: MentionMatcher,
        historical: Boolean = false,
    ): ChatItem? {
        val channel = msg.channel ?: return null
        return when (msg.command) {
            "PRIVMSG" -> buildPrivmsg(msg, channel, selfLogin, channelId, mentions, historical)
            "USERNOTICE" -> buildUserNotice(msg, channel, selfLogin, channelId, mentions, historical)
            "NOTICE" -> msg.trailing?.let {
                ChatItem(id = UUID.randomUUID().toString(), channel = channel, kind = MessageKind.Notice,
                    timestamp = System.currentTimeMillis(), systemText = it, text = it, historical = historical)
            }
            else -> null
        }
    }

    /** Local echo of a message the user sent; Twitch does not send it back. */
    fun buildOwn(
        channel: String,
        text: String,
        userState: Map<String, String>,
        selfLogin: String,
        channelId: String?,
        reply: ReplyInfo?,
        /** The segments of the answered message, already built. */
        quote: List<Segment> = emptyList(),
    ): ChatItem {
        val (body, isAction) = splitAction(text)
        return ChatItem(
            id = "local-" + UUID.randomUUID(),
            channel = channel,
            kind = if (isAction) MessageKind.Action else MessageKind.Chat,
            timestamp = System.currentTimeMillis(),
            login = selfLogin,
            displayName = userState["display-name"]?.ifEmpty { null } ?: selfLogin,
            color = parseColor(userState["color"]),
            body = deferred(
                channel, body, emotesTag = null, badgesTag = userState["badges"],
                userId = userState["user-id"], channelId = channelId, ownMessage = true,
                quote = { quote },
            ),
            text = body,
            isOwn = true,
            reply = reply,
        )
    }

    /**
     * The drawing part of a message, built later. Keeps only the tag values it needs instead of the
     * whole [IrcMessage].
     *
     * [strippedPrefix] is the "@parent " of a reply: Twitch's emote positions count from the
     * untrimmed text, so they are shifted after parsing.
     */
    private fun deferred(
        channel: String,
        rawBody: String,
        emotesTag: String?,
        badgesTag: String?,
        userId: String?,
        channelId: String?,
        ownMessage: Boolean,
        strippedPrefix: Int = 0,
        /**
         * Where the badges were earned: a Shared Chat partner's message shows that channel's
         * badges.
         */
        badgeChannelId: String? = channelId,
        quote: () -> List<Segment> = { emptyList() },
        /** Only a message that carries bits turns "Cheer100" into a cheermote. */
        cheered: Boolean = false,
    ) = MessageBody.lazily(
        segments = {
            val text = if (strippedPrefix == 0) rawBody else rawBody.substring(strippedPrefix)
            var ranges = twitchEmotes(rawBody, emotesTag)
            if (strippedPrefix > 0) {
                ranges = ranges.mapNotNull { r ->
                    if (r.start < strippedPrefix) null
                    else r.copy(start = r.start - strippedPrefix, end = r.end - strippedPrefix)
                }
            }
            segments(channel, text, ranges, channelId, ownMessage, cheered)
        },
        badges = { badges.resolve(badgeChannelId, badgesTag, userId) },
        paint = { badges.paint(userId) },
        quote = quote,
        // Asked after building: if a provider still owes emotes, the message is rebuilt once they
        // arrive.
        worthKeeping = { !emotes.complete(channelId) },
    )

    private fun buildPrivmsg(
        msg: IrcMessage, channel: String, selfLogin: String, channelId: String?,
        mentions: MentionMatcher, historical: Boolean,
    ): ChatItem {
        val login = msg.nick.orEmpty()
        val (raw, isAction) = splitAction(msg.trailing.orEmpty())
        val reply = replyInfo(msg)

        // Twitch prefixes replies with "@<display name> ", not the login. A display name in other
        // letters (Japanese, Cyrillic) would never match the login, so the display name is tried
        // first; the login is the fallback when the tag is missing.
        val stripped = reply?.let { r ->
            listOf(r.parentDisplayName, r.parentLogin)
                .firstOrNull { it.isNotEmpty() && raw.startsWith("@$it ", ignoreCase = true) }
                ?.let { it.length + 2 }
        } ?: 0
        val body = if (stripped == 0) raw else raw.substring(stripped)

        val isOwn = login.equals(selfLogin, ignoreCase = true)
        val roomId = channelId ?: msg.tag("room-id")
        val partner = partnerRoom(msg, roomId)
        val bits = msg.tagLong("bits")?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
        return ChatItem(
            id = msg.tag("id") ?: UUID.randomUUID().toString(),
            channel = channel,
            kind = if (isAction) MessageKind.Action else MessageKind.Chat,
            timestamp = timestamp(msg, historical),
            login = login,
            displayName = msg.tag("display-name") ?: login,
            color = parseColor(msg.tag("color")),
            body = deferred(
                channel, raw, emotesTag = msg.tag("emotes"), badgesTag = badgesOf(msg),
                userId = msg.tag("user-id"), channelId = roomId,
                ownMessage = false, strippedPrefix = stripped, badgeChannelId = partner ?: roomId,
                quote = { reply?.let { quoteSegments(channel, it.parentBody, roomId) }.orEmpty() },
                cheered = bits > 0,
            ),
            text = body,
            isMention = !isOwn && (mentions.matches(body) || reply?.parentLogin.equals(selfLogin, ignoreCase = true)),
            isFirstMessage = !isOwn && msg.tagIs("first-msg", "1"),
            isOwn = isOwn,
            reply = reply,
            historical = historical,
            sharedId = msg.tag("source-id"),
            sourceRoomId = partner,
            bits = bits,
        )
    }

    private fun buildUserNotice(
        msg: IrcMessage, channel: String, selfLogin: String, channelId: String?,
        mentions: MentionMatcher, historical: Boolean,
    ): ChatItem {
        val body = msg.trailing.orEmpty()
        val login = msg.tag("login")
        val roomId = channelId ?: msg.tag("room-id")
        val partner = partnerRoom(msg, roomId)
        return ChatItem(
            id = msg.tag("id") ?: UUID.randomUUID().toString(),
            channel = channel,
            kind = MessageKind.UserNotice,
            timestamp = timestamp(msg, historical),
            login = login,
            displayName = msg.tag("display-name") ?: login,
            color = parseColor(msg.tag("color")),
            // A notice without a message of its own (a plain sub, a raid) shows no badges.
            body = if (body.isEmpty()) MessageBody.EMPTY else deferred(
                channel, body, emotesTag = msg.tag("emotes"), badgesTag = badgesOf(msg),
                userId = msg.tag("user-id"), channelId = roomId,
                ownMessage = false, badgeChannelId = partner ?: roomId,
            ),
            systemText = msg.tag("system-msg"),
            text = body,
            isMention = body.isNotEmpty() && !login.equals(selfLogin, ignoreCase = true) && mentions.matches(body),
            historical = historical,
            sharedId = msg.tag("source-id"),
            sourceRoomId = partner,
        )
    }

    /**
     * The Shared Chat partner a message was written in, if not the channel it arrived in. During a
     * session the channel's own messages carry the tag too, naming the channel itself.
     */
    private fun partnerRoom(msg: IrcMessage, roomId: String?): String? =
        msg.tag("source-room-id")?.takeIf { it != (msg.tag("room-id") ?: roomId) }

    /**
     * The badges where the sender wrote. In Shared Chat `badges` refers to the channel the copy
     * arrived in, not where a sub or moderator badge was earned.
     */
    private fun badgesOf(msg: IrcMessage): String? = msg.tag("source-badges") ?: msg.tag("badges")

    internal data class EmoteRange(val start: Int, val end: Int, val id: String, val name: String)

    /**
     * Parses the `emotes` tag ("25:0-4,12-16/1902:6-10"). Twitch counts code points, Kotlin strings
     * UTF-16 units, so an emoji before an emote shifts the positions.
     */
    internal fun twitchEmotes(text: String, tag: String?): List<EmoteRange> {
        if (tag.isNullOrEmpty()) return emptyList()
        val result = ArrayList<EmoteRange>()
        val hasSurrogates = text.any { it.isSurrogate() }
        val cpCount = if (hasSurrogates) text.codePointCount(0, text.length) else text.length
        fun charIndex(cp: Int) = if (hasSurrogates) text.offsetByCodePoints(0, cp) else cp

        for (entry in tag.split('/')) {
            val colon = entry.indexOf(':')
            if (colon <= 0) continue
            val id = entry.substring(0, colon)
            for (range in entry.substring(colon + 1).split(',')) {
                val dash = range.indexOf('-')
                if (dash <= 0) continue
                val startCp = range.substring(0, dash).toIntOrNull() ?: continue
                val endCp = range.substring(dash + 1).toIntOrNull() ?: continue
                if (startCp < 0 || endCp >= cpCount || endCp < startCp) continue
                val start = charIndex(startCp)
                val end = charIndex(endCp + 1)
                result += EmoteRange(start, end, id, text.substring(start, end))
            }
        }
        result.sortBy { it.start }
        return result
    }

    internal fun segments(
        channel: String,
        text: String,
        twitchRanges: List<EmoteRange>,
        channelId: String?,
        ownMessage: Boolean,
        cheered: Boolean = false,
    ): List<Segment> {
        val opts = options()
        // With emotes off, every word stays text, Twitch emotes included.
        val twitch = if (opts.enabled && EmoteProvider.Twitch in opts.providers) twitchRanges else emptyList()
        val out = ArrayList<Segment>()
        val buf = StringBuilder()
        var nextTwitch = 0
        var i = 0
        val len = text.length

        fun flush() {
            if (buf.isNotEmpty()) {
                out += Segment.Text(buf.toString())
                buf.setLength(0)
            }
        }

        fun addEmote(emote: Emote) {
            val last = out.lastOrNull()
            if (emote.zeroWidth && opts.zeroWidth && last is Segment.EmoteSeg && buf.isBlank()) {
                buf.setLength(0)
                out[out.lastIndex] = last.copy(overlays = last.overlays + emote)
            } else {
                flush()
                out += Segment.EmoteSeg(emote)
            }
        }

        while (i < len) {
            if (text[i] == ' ') {
                buf.append(' ')
                i++
                continue
            }
            while (nextTwitch < twitch.size && twitch[nextTwitch].start < i) nextTwitch++
            val range = twitch.getOrNull(nextTwitch)
            if (range != null && range.start == i) {
                addEmote(Emote(range.name, range.id, twitchEmoteUrl(range.id), EmoteProvider.Twitch))
                i = range.end
                nextTwitch++
                continue
            }

            var end = text.indexOf(' ', i)
            if (end == -1) end = len
            val word = text.substring(i, end)
            val cheer = if (cheered) cheer(channelId, word) else null
            if (cheer != null) {
                flush()
                out += cheer
                i = end
                continue
            }
            val emote = if (!opts.enabled) null else {
                ((if (ownMessage) emotes.lookupOwnTwitch(channelId, word) else null) ?: emotes.lookup(channelId, word))
                    ?.takeIf { it.provider in opts.providers }
                    ?.takeIf { opts.showUnlisted || !it.unlisted }
            }
            when {
                emote != null -> addEmote(emote)
                isLink(word) -> {
                    flush()
                    out += Segment.Link(word, if (word.startsWith("http", ignoreCase = true)) word else "https://$word")
                }
                word.length > 1 && word[0] == '@' -> {
                    flush()
                    out += mention(channel, word)
                }
                else -> buf.append(word)
            }
            i = end
        }
        flush()
        return out
    }

    /**
     * [word] as a cheer, if it is a known prefix followed by an amount: "Cheer100", "4Head50". The
     * amount is the trailing digits, so a prefix may contain digits of its own.
     */
    private fun cheer(channelId: String?, word: String): Segment.Cheer? {
        val digits = word.takeLastWhile { it.isDigit() }
        if (digits.isEmpty() || digits.length == word.length || digits.length > 9) return null
        val amount = digits.toInt().takeIf { it > 0 } ?: return null
        val prefix = word.dropLast(digits.length)
        val tier = cheers.lookup(channelId, prefix)?.tierFor(amount) ?: return null
        fun image(url: String) = Emote(word, "cheer:$prefix:${tier.minBits}", url, EmoteProvider.Twitch)
        return Segment.Cheer(image(tier.darkUrl), image(tier.lightUrl), amount, tier.color)
    }

    /**
     * "@name" with the mentioned user's chat color, only while they chat in this channel. Trailing
     * punctuation ("@name," / "@name?") is ignored for the lookup.
     */
    private fun mention(channel: String, word: String): Segment.Mention {
        val login = word.drop(1).trimEnd { !it.isLetterOrDigit() && it != '_' }
        val chatter = login.takeIf { it.isNotEmpty() }?.let { chatters.find(channel, it) }
            ?: return Segment.Mention(word)
        return Segment.Mention(word, login = login, color = chatter.color)
    }

    /**
     * The answered message for the line above a reply. Twitch sends its text without emote
     * positions, so only emotes known by name are found. Internal for tests.
     */
    internal fun quoteSegments(channel: String, text: String, channelId: String?): List<Segment> =
        if (text.isEmpty()) emptyList() else segments(channel, text, emptyList(), channelId, ownMessage = false)

    private fun replyInfo(msg: IrcMessage): ReplyInfo? {
        val id = msg.tag("reply-parent-msg-id") ?: return null
        val login = msg.tag("reply-parent-user-login").orEmpty()
        return ReplyInfo(
            parentId = id,
            parentLogin = login,
            parentDisplayName = msg.tag("reply-parent-display-name") ?: login,
            parentBody = msg.tag("reply-parent-msg-body").orEmpty(),
            threadId = msg.tag("reply-thread-parent-msg-id") ?: id,
        )
    }

    private fun timestamp(msg: IrcMessage, historical: Boolean): Long =
        (if (historical) msg.tagLong("rm-received-ts") else null)
            ?: msg.tagLong("tmi-sent-ts")
            ?: System.currentTimeMillis()

    companion object {
        private val DOMAIN = Regex("^[\\w-]+(\\.[\\w-]+)*\\.[a-zA-Z]{2,24}(/\\S*)?$")

        fun isLink(word: String): Boolean =
            word.startsWith("https://", ignoreCase = true) || word.startsWith("http://", ignoreCase = true) ||
                (word.contains('.') && DOMAIN.matches(word))

        /** "\u0001ACTION waves\u0001" (from /me) -> ("waves", true). */
        fun splitAction(text: String): Pair<String, Boolean> =
            if (text.startsWith("\u0001ACTION ") && text.endsWith("\u0001") && text.length >= 9) {
                text.substring(8, text.length - 1) to true
            } else text to false

        /** "#1E90FF" as an opaque ARGB color, read in place for every message. */
        fun parseColor(hex: String?): Int? {
            if (hex == null || hex.length != 7 || hex[0] != '#') return null
            var rgb = 0
            for (i in 1..6) {
                val digit = Character.digit(hex[i], 16)
                if (digit < 0) return null
                rgb = rgb shl 4 or digit
            }
            return rgb or 0xFF000000.toInt()
        }
    }
}
