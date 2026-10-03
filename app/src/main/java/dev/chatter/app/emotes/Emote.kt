package dev.chatter.app.emotes

enum class EmoteProvider { Twitch, SevenTv, Bttv, Ffz }

/** The provider's name for messages and cards. Product names, not translated. */
val EmoteProvider.label: String
    get() = when (this) {
        EmoteProvider.Twitch -> "Twitch"
        EmoteProvider.SevenTv -> "7TV"
        EmoteProvider.Bttv -> "BetterTTV"
        EmoteProvider.Ffz -> "FrankerFaceZ"
    }

data class Emote(
    val name: String,
    val id: String,
    val url: String,
    val provider: EmoteProvider,
    /** width / height, to size the placeholder before the image loads. */
    val aspectRatio: Float = 1f,
    /** False if the provider does not report the size (BTTV); it is measured once loaded. */
    val sizeKnown: Boolean = true,
    /** Drawn on top of the previous emote instead of next to it. */
    val zeroWidth: Boolean = false,
    /** Channel emote rather than a global one. */
    val isChannel: Boolean = false,
    /** 7TV emote not approved for public listing. */
    val unlisted: Boolean = false,
    /** Creator or uploader, if the provider reports one. */
    val author: String? = null,
) {
    /** Largest size, for the emote card. */
    val largeUrl: String
        get() = when (provider) {
            EmoteProvider.Twitch -> url.replace("/2.0", "/3.0")
            EmoteProvider.SevenTv -> url.replace("/2x.webp", "/4x.webp")
            EmoteProvider.Bttv -> url.replace("/2x.webp", "/3x.webp")
            EmoteProvider.Ffz -> url.replace(Regex("/[12]$"), "/4")
        }

    /**
     * Smallest size, for saving data: a quarter of the pixels of [url], slightly soft on sharp
     * screens.
     */
    val smallUrl: String
        get() = when (provider) {
            EmoteProvider.Twitch -> url.replace("/2.0", "/1.0")
            EmoteProvider.SevenTv -> url.replace("/2x.webp", "/1x.webp")
            EmoteProvider.Bttv -> url.replace("/2x.webp", "/1x.webp")
            EmoteProvider.Ffz -> url.replace(Regex("/[24]$"), "/1")
        }

    /** The emote's page on the provider's website; Twitch has none. */
    val pageUrl: String?
        get() = when (provider) {
            EmoteProvider.Twitch -> null
            EmoteProvider.SevenTv -> "https://7tv.app/emotes/$id"
            EmoteProvider.Bttv -> "https://betterttv.com/emotes/$id"
            EmoteProvider.Ffz -> "https://www.frankerfacez.com/emoticon/$id"
        }
}

fun twitchEmoteUrl(id: String) = "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/2.0"
