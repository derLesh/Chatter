package dev.chatter.app.emotes

import org.junit.Assert.assertEquals
import org.junit.Test

/** The sizes an emote is asked for in: each provider writes the size into its urls differently. */
class EmoteUrlsTest {
    private fun emote(url: String, provider: EmoteProvider) = Emote("e", "1", url, provider)

    @Test
    fun smallUrlAsksEveryProviderForItsSmallestSize() {
        assertEquals(
            "https://static-cdn.jtvnw.net/emoticons/v2/25/default/dark/1.0",
            emote(twitchEmoteUrl("25"), EmoteProvider.Twitch).smallUrl,
        )
        assertEquals(
            "https://cdn.7tv.app/emote/abc/1x.webp",
            emote("https://cdn.7tv.app/emote/abc/2x.webp", EmoteProvider.SevenTv).smallUrl,
        )
        assertEquals(
            "https://cdn.betterttv.net/emote/abc/1x.webp",
            emote("https://cdn.betterttv.net/emote/abc/2x.webp", EmoteProvider.Bttv).smallUrl,
        )
        assertEquals(
            "https://cdn.frankerfacez.com/emote/42/1",
            emote("https://cdn.frankerfacez.com/emote/42/2", EmoteProvider.Ffz).smallUrl,
        )
        assertEquals(
            "https://cdn.frankerfacez.com/emote/42/animated/1",
            emote("https://cdn.frankerfacez.com/emote/42/animated/2", EmoteProvider.Ffz).smallUrl,
        )
    }

    @Test
    fun anFfzEmoteWithOnlyOneSizeStaysAsItIs() {
        val url = "https://cdn.frankerfacez.com/emote/42/1"
        assertEquals(url, emote(url, EmoteProvider.Ffz).smallUrl)
    }
}
