package dev.chatter.app.emotes

enum class EmoteProvider { Twitch, SevenTv, Bttv, Ffz }

data class Emote(
    val name: String,
    val id: String,
    val url: String,
    val provider: EmoteProvider,
    /** width / height, used to size the inline placeholder before the image is loaded. */
    val aspectRatio: Float = 1f,
    /** False if the provider does not tell the size (BTTV); it is then measured once loaded. */
    val sizeKnown: Boolean = true,
    /** Zero-width emotes are drawn on top of the previous emote instead of next to it. */
    val zeroWidth: Boolean = false,
    /** True for channel-specific emotes (as opposed to global ones). */
    val isChannel: Boolean = false,
    /** 7TV emotes that are not publicly listed (not approved by 7TV moderators). */
    val unlisted: Boolean = false,
)

fun twitchEmoteUrl(id: String) = "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/2.0"
