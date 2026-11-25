package me.lesh.chatter.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedItemsTest {
    private val hosts = listOf("imgur.com")
    private val sites = setOf(PostSite.YouTube, PostSite.Twitch, PostSite.X)

    private fun message(id: String, vararg segments: Segment, deleted: Boolean = false, at: Long = id.hashCode().toLong()) = ChatItem(
        id = id,
        channel = "channel",
        kind = MessageKind.Chat,
        timestamp = at,
        login = "poster",
        displayName = "Poster",
        body = MessageBody.of(segments.toList()),
        deleted = deleted,
    )

    private fun link(url: String) = Segment.Link(url, url)

    private fun find(vararg messages: ChatItem, gifs: Boolean = true) =
        SharedItemsFinder.find(messages.toList(), hosts, sites, gifs)

    @Test
    fun picturesGifsAndVideosAreMediaAndTheRestAreLinks() {
        val found = find(
            message(
                "1",
                link("https://i.imgur.com/a.png"),
                Segment.Text(" look "),
                link("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
                link("https://github.com/derLesh/Chatter"),
                Segment.Gif("[Yes GIF]", "https://media4.giphy.com/media/x/giphy.gif"),
            ),
        )
        assertEquals(
            listOf("https://i.imgur.com/a.png", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "https://media4.giphy.com/media/x/giphy.gif"),
            found.media.map { it.url },
        )
        assertTrue(found.media[2] is SharedMedia.Gif)
        assertEquals(listOf("https://github.com/derLesh/Chatter"), found.links.map { it.url })
        assertEquals("1", found.links.single().postedIn.messageId)
    }

    @Test
    fun theNewestComesFirstAndAnAddressPostedTwiceKeepsItsNewestMessage() {
        val found = find(
            message("old", link("https://example.com/a")),
            message("middle", link("https://example.com/b")),
            message("new", link("https://example.com/a")),
        )
        assertEquals(listOf("https://example.com/a", "https://example.com/b"), found.links.map { it.url })
        assertEquals("new", found.links.first().postedIn.messageId)
    }

    @Test
    fun deletedMessagesShareNothing() {
        val found = find(message("1", link("https://example.com/a"), deleted = true))
        assertEquals(SharedItems.EMPTY, found)
    }

    @Test
    fun onlyWhatTheChatShowsAsPictureOrVideoIsMedia() {
        val found = SharedItemsFinder.find(
            listOf(
                message(
                    "1",
                    link("https://i.imgur.com/a.png"),
                    link("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
                    Segment.Gif("[Yes GIF]", "https://media4.giphy.com/media/x/giphy.gif"),
                ),
            ),
            imageHosts = emptyList(),
            postSites = emptySet(),
            gifs = false,
        )
        // A GIF without its picture has no address of its own to list.
        assertEquals(emptyList<SharedMedia>(), found.media)
        assertEquals(
            listOf("https://i.imgur.com/a.png", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
            found.links.map { it.url },
        )
    }

    @Test
    fun aTwitchChannelOrAnXPostIsALinkAndAClipIsAVideo() {
        val found = find(
            message(
                "1",
                link("https://www.twitch.tv/somechannel"),
                link("https://x.com/someone/status/1234567890"),
                link("https://clips.twitch.tv/FunnyClipSlug-abc123"),
            ),
        )
        assertEquals(listOf("https://clips.twitch.tv/FunnyClipSlug-abc123"), found.media.map { it.url })
        assertEquals(listOf("https://www.twitch.tv/somechannel", "https://x.com/someone/status/1234567890"), found.links.map { it.url })
    }
}
