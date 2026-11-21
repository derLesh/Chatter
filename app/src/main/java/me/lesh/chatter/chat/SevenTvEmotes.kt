package me.lesh.chatter.chat

import me.lesh.chatter.net.SevenTvApi
import me.lesh.chatter.net.SevenTvEmoteInfo
import me.lesh.chatter.net.TrustedImages
import okhttp3.HttpUrl

/** Emote pages on 7TV: which links are one, and how 7TV's answer becomes a [Post]. A key is the emote's id. */
object SevenTvEmotes {
    private val HOSTS = setOf("7tv.app", "old.7tv.app")

    /** 7TV's ids are ULIDs; emotes from before 2023 are also linked by their old 24-digit id. */
    private val ID = Regex("[0-9A-Za-z]{26}|[0-9a-f]{24}")

    fun key(url: HttpUrl): String? {
        if (url.host.removePrefix("www.") !in HOSTS) return null
        val segments = PostLinks.segments(url)
        if (segments.size != 2 || segments[0] != "emotes") return null
        return segments[1].takeIf { ID.matches(it) }
    }

    /** [info] as its card shows it, or null without a picture to show. */
    fun emote(info: SevenTvEmoteInfo): Post? {
        val host = info.host ?: return null
        // The largest webp: the card draws the emote much bigger than the chat does.
        val file = host.files.filter { it.format.equals("WEBP", ignoreCase = true) }.maxByOrNull { it.width }
        val picture = TrustedImages.url("${host.url}/${file?.name ?: "4x.webp"}") ?: return null
        val aspect = if (file != null && file.height > 0) file.width.toFloat() / file.height else 1f
        val creator = info.owner?.displayName?.ifEmpty { info.owner.username }.orEmpty()
        return Post(
            name = info.name, handle = "", avatarUrl = null, text = "",
            replies = 0, reposts = 0, likes = 0, postedAt = 0, nsfw = false, media = null,
            emote = PostEmote(info.id, picture, creator, aspect),
        )
    }
}

/** Read from 7TV's public API, without a login. */
class SevenTvPostSource(private val api: SevenTvApi) : PostSource {
    override val site = PostSite.SevenTv

    override suspend fun fetch(key: String): Post? = api.emote(key)?.let(SevenTvEmotes::emote)
}
