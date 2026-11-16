package me.lesh.chatter.chat

import me.lesh.chatter.net.AppJson
import me.lesh.chatter.net.SevenTvEmoteInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SevenTvEmotesTest {
    private fun key(url: String) = PostLinks.find(url, setOf(PostSite.SevenTv))?.key

    @Test
    fun anEmotePageIsItsId() {
        for (url in listOf(
            "https://7tv.app/emotes/$ID",
            "https://7tv.app/emotes/$ID/",
            "https://www.7tv.app/emotes/$ID?tab=channels",
            "https://old.7tv.app/emotes/$ID",
            "http://7tv.app/emotes/$ID",
        )) assertEquals(url, ID, key(url))
    }

    @Test
    fun anOldEmoteIdIsFoundToo() {
        assertEquals("60ae958e229664e8667aea38", key("https://7tv.app/emotes/60ae958e229664e8667aea38"))
    }

    @Test
    fun otherPagesOf7tvAreNoEmote() {
        for (url in listOf(
            "https://7tv.app/",
            "https://7tv.app/emotes",
            "https://7tv.app/emotes?query=pepe",
            "https://7tv.app/emote-sets/$ID",
            "https://7tv.app/users/$ID",
            "https://7tv.app/emotes/$ID/extra",
            "https://7tv.app/emotes/not-an-id",
            "https://cdn.7tv.app/emote/$ID/4x.webp",
            "https://7tv.app.evil.example/emotes/$ID",
            "https://user@7tv.app/emotes/$ID",
        )) assertNull(url, key(url))
    }

    @Test
    fun anEmoteBecomesACardWithItsLargestPictureAndCreator() {
        val post = SevenTvEmotes.emote(AppJson.decodeFromString<SevenTvEmoteInfo>(EMOTE))!!
        assertEquals("Prayge", post.name)
        val emote = post.emote!!
        assertEquals(ID, emote.id)
        assertEquals("https://cdn.7tv.app/emote/$ID/4x.webp", emote.pictureUrl)
        assertEquals("Neowav", emote.creator)
        assertEquals(2f, emote.aspectRatio)
    }

    @Test
    fun anEmoteWithoutPicturesOrFromAnotherHostHasNoCard() {
        assertNull(SevenTvEmotes.emote(SevenTvEmoteInfo(id = ID, name = "Prayge")))
        val elsewhere = EMOTE.replace("//cdn.7tv.app", "//evil.example")
        assertNull(SevenTvEmotes.emote(AppJson.decodeFromString<SevenTvEmoteInfo>(elsewhere)))
    }

    @Test
    fun aCreatorWithoutDisplayNameIsNamedByLogin() {
        val json = EMOTE.replace("\"display_name\":\"Neowav\"", "\"display_name\":\"\"")
        assertEquals("neowav", SevenTvEmotes.emote(AppJson.decodeFromString<SevenTvEmoteInfo>(json))!!.emote!!.creator)
    }

    private companion object {
        const val ID = "01F6NACCD80006SZ7ZW5FMWKWK"

        /** Shortened from 7tv.io/v3/emotes/<id>; the emote is made twice as wide as it is. */
        val EMOTE = """
            {"id":"$ID","name":"Prayge","animated":false,
             "owner":{"id":"01F6GWABR80004XXYV3V0YKDSG","username":"neowav","display_name":"Neowav"},
             "host":{"url":"//cdn.7tv.app/emote/$ID","files":[
               {"name":"1x.webp","width":64,"height":32,"format":"WEBP"},
               {"name":"4x.webp","width":256,"height":128,"format":"WEBP"},
               {"name":"4x.avif","width":256,"height":128,"format":"AVIF"},
               {"name":"2x.webp","width":128,"height":64,"format":"WEBP"}]}}
        """.trimIndent()
    }
}
