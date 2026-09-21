package dev.chatter.app.chat

import dev.chatter.app.badges.Badge
import dev.chatter.app.emotes.Emote
import dev.chatter.app.emotes.EmoteProvider
import dev.chatter.app.emotes.twitchEmoteUrl
import dev.chatter.app.irc.IrcMessage
import java.util.UUID

/** Where the builder gets emotes and badges from. Implemented by the repositories, faked in tests. */
interface EmoteSource {
    fun lookup(channelId: String?, word: String): Emote?
    /** Twitch emotes the user may use (their emote sets plus follower emotes of the channel). */
    fun lookupOwnTwitch(channelId: String?, word: String): Emote?
}

/** User preferences that change how emotes are recognized in new messages. */
data class EmoteOptions(
    val enabled: Boolean = true,
    val zeroWidth: Boolean = true,
    val showUnlisted: Boolean = false,
    /** Emotes from other providers are left as plain text. */
    val providers: Set<EmoteProvider> = EmoteProvider.entries.toSet(),
)

fun interface BadgeSource {
    /** [userId] comes from the `user-id` tag: badges from other clients hang off it, not off the tag. */
    fun resolve(channelId: String?, badgesTag: String?, userId: String?): List<Badge>
}

/** Turns raw IRC messages into [ChatItem]s. All the parsing work happens here, once per message. */
class MessageBuilder(
    private val emotes: EmoteSource,
    private val badges: BadgeSource,
    private val chatters: ChatterRegistry = ChatterRegistry(),
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

    /** Local echo for a message we just sent. Twitch never sends our own PRIVMSG back to us. */
    fun buildOwn(
        channel: String,
        text: String,
        userState: Map<String, String>,
        selfLogin: String,
        channelId: String?,
        reply: ReplyInfo?,
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
            ),
            text = body,
            isOwn = true,
            reply = reply,
        )
    }

    /**
     * The drawing half of a message, left for whoever draws it. Holds on to the few tag values it
     * needs rather than to the whole [IrcMessage], so a buffered message keeps nothing alive that
     * it would not have kept anyway.
     *
     * [strippedPrefix] is the "@parent " a reply carries: the emote positions Twitch sends count
     * from the untrimmed text, so they can only be shifted once they are parsed.
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
            segments(channel, text, ranges, channelId, ownMessage)
        },
        badges = { badges.resolve(channelId, badgesTag, userId) },
    )

    private fun buildPrivmsg(
        msg: IrcMessage, channel: String, selfLogin: String, channelId: String?,
        mentions: MentionMatcher, historical: Boolean,
    ): ChatItem {
        val login = msg.nick.orEmpty()
        val (raw, isAction) = splitAction(msg.trailing.orEmpty())
        val reply = replyInfo(msg)

        // Twitch prefixes replies with "@<display name> ", not with the login, and the reply header
        // already shows who is addressed. A display name that is the login in other letters
        // (Japanese, Cyrillic) would otherwise never match and stay in the text. The login is only
        // the fallback for a message that carries no display name for the parent.
        val stripped = reply?.let { r ->
            listOf(r.parentDisplayName, r.parentLogin)
                .firstOrNull { it.isNotEmpty() && raw.startsWith("@$it ", ignoreCase = true) }
                ?.let { it.length + 2 }
        } ?: 0
        val body = if (stripped == 0) raw else raw.substring(stripped)

        val isOwn = login.equals(selfLogin, ignoreCase = true)
        return ChatItem(
            id = msg.tag("id") ?: UUID.randomUUID().toString(),
            channel = channel,
            kind = if (isAction) MessageKind.Action else MessageKind.Chat,
            timestamp = timestamp(msg, historical),
            login = login,
            displayName = msg.tag("display-name") ?: login,
            color = parseColor(msg.tag("color")),
            body = deferred(
                channel, raw, emotesTag = msg.tag("emotes"), badgesTag = msg.tag("badges"),
                userId = msg.tag("user-id"), channelId = channelId ?: msg.tag("room-id"),
                ownMessage = false, strippedPrefix = stripped,
            ),
            text = body,
            isMention = !isOwn && (mentions.matches(body) || reply?.parentLogin.equals(selfLogin, ignoreCase = true)),
            isFirstMessage = !isOwn && msg.tag("first-msg") == "1",
            isOwn = isOwn,
            reply = reply,
            historical = historical,
        )
    }

    private fun buildUserNotice(
        msg: IrcMessage, channel: String, selfLogin: String, channelId: String?,
        mentions: MentionMatcher, historical: Boolean,
    ): ChatItem {
        val body = msg.trailing.orEmpty()
        val login = msg.tag("login")
        return ChatItem(
            id = msg.tag("id") ?: UUID.randomUUID().toString(),
            channel = channel,
            kind = MessageKind.UserNotice,
            timestamp = timestamp(msg, historical),
            login = login,
            displayName = msg.tag("display-name") ?: login,
            color = parseColor(msg.tag("color")),
            // A notice without a message of its own (a plain sub, a raid) shows no badges either.
            body = if (body.isEmpty()) MessageBody.EMPTY else deferred(
                channel, body, emotesTag = msg.tag("emotes"), badgesTag = msg.tag("badges"),
                userId = msg.tag("user-id"), channelId = channelId ?: msg.tag("room-id"),
                ownMessage = false,
            ),
            systemText = msg.tag("system-msg"),
            text = body,
            isMention = body.isNotEmpty() && !login.equals(selfLogin, ignoreCase = true) && mentions.matches(body),
            historical = historical,
        )
    }

    internal data class EmoteRange(val start: Int, val end: Int, val id: String, val name: String)

    /**
     * Parses the `emotes` tag ("25:0-4,12-16/1902:6-10"). Twitch counts in Unicode code points,
     * Kotlin strings in UTF-16 chars, so emoji before an emote shift the positions.
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

    internal fun segments(channel: String, text: String, twitchRanges: List<EmoteRange>, channelId: String?, ownMessage: Boolean): List<Segment> {
        val opts = options()
        // With emotes turned off, every word (Twitch emotes included) stays plain text.
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
     * "@name" plus the mentioned user's chat color, but only while they are chatting in this
     * channel. Trailing punctuation ("@name," / "@name?") still has to find the user.
     */
    private fun mention(channel: String, word: String): Segment.Mention {
        val login = word.drop(1).trimEnd { !it.isLetterOrDigit() && it != '_' }
        val chatter = login.takeIf { it.isNotEmpty() }?.let { chatters.find(channel, it) }
            ?: return Segment.Mention(word)
        return Segment.Mention(word, login = login, color = chatter.color)
    }

    private fun replyInfo(msg: IrcMessage): ReplyInfo? {
        val id = msg.tag("reply-parent-msg-id") ?: return null
        val login = msg.tag("reply-parent-user-login").orEmpty()
        return ReplyInfo(
            parentId = id,
            parentLogin = login,
            parentDisplayName = msg.tag("reply-parent-display-name") ?: login,
            parentBody = msg.tag("reply-parent-msg-body").orEmpty(),
        )
    }

    private fun timestamp(msg: IrcMessage, historical: Boolean): Long =
        (if (historical) msg.tag("rm-received-ts") else null)?.toLongOrNull()
            ?: msg.tag("tmi-sent-ts")?.toLongOrNull()
            ?: System.currentTimeMillis()

    companion object {
        private val DOMAIN = Regex("^[\\w-]+(\\.[\\w-]+)*\\.[a-zA-Z]{2,24}(/\\S*)?$")

        fun isLink(word: String): Boolean =
            word.startsWith("https://", ignoreCase = true) || word.startsWith("http://", ignoreCase = true) ||
                (word.contains('.') && DOMAIN.matches(word))

        /** "\u0001ACTION waves\u0001" (sent by /me) -> ("waves", true). */
        fun splitAction(text: String): Pair<String, Boolean> =
            if (text.startsWith("\u0001ACTION ") && text.endsWith("\u0001") && text.length >= 9) {
                text.substring(8, text.length - 1) to true
            } else text to false

        fun parseColor(hex: String?): Int? {
            if (hex == null || hex.length != 7 || hex[0] != '#') return null
            return hex.substring(1).toIntOrNull(16)?.let { it or 0xFF000000.toInt() }
        }
    }
}
