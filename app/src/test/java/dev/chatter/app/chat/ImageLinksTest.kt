package dev.chatter.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageLinksTest {
    private val hosts = ImageLinks.DEFAULT_HOSTS

    private fun link(url: String) = Segment.Link(url, url)

    @Test
    fun aPictureOnAnAllowedHostIsTheUrlItself() {
        assertEquals("https://i.imgur.com/abc.png", ImageLinks.imageUrl("https://i.imgur.com/abc.png", hosts))
        assertEquals("https://i.redd.it/x.JPEG", ImageLinks.imageUrl("https://i.redd.it/x.JPEG", hosts))
        // A size or a cache buster hanging off the url does not hide the file name.
        assertEquals(
            "https://cdn.discordapp.com/a/b.webp?width=300#top",
            ImageLinks.imageUrl("https://cdn.discordapp.com/a/b.webp?width=300#top", hosts),
        )
    }

    @Test
    fun onlyPlainPicturesAreEverFetched() {
        // An SVG carries scripts, and everything else is a file the app cannot name.
        assertNull(ImageLinks.imageUrl("https://i.imgur.com/a.svg", hosts))
        assertNull(ImageLinks.imageUrl("https://i.imgur.com/a.html", hosts))
        assertNull(ImageLinks.imageUrl("https://i.imgur.com/a.php?x=.png", hosts))
        assertEquals("https://i.imgur.com/a.webp", ImageLinks.imageUrl("https://i.imgur.com/a.webp", hosts))
        assertEquals("https://i.imgur.com/a.gif", ImageLinks.imageUrl("https://i.imgur.com/a.gif", hosts))
    }

    @Test
    fun nothingButHttpIsEverFollowed() {
        assertNull(ImageLinks.imageUrl("javascript:alert(1)//imgur.com/a.png", hosts))
        assertNull(ImageLinks.imageUrl("data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=", hosts))
        assertNull(ImageLinks.imageUrl("file:///sdcard/imgur.com/a.png", hosts))
        assertNull(ImageLinks.imageUrl("content://imgur.com/a.png", hosts))
    }

    @Test
    fun aHostDressedUpAsAnAllowedOneIsStillItself() {
        assertNull(ImageLinks.imageUrl("https://imgur.com@evil.example/a.png", hosts))
        assertNull(ImageLinks.imageUrl("https://evil.example/imgur.com/a.png", hosts))
        assertNull(ImageLinks.imageUrl("https://i.imgur.com.evil.example/a.png", hosts))
    }

    @Test
    fun anIdThisBuildsAUrlFromCanOnlyBeLettersAndDigits() {
        // Anything that could steer the address somewhere else stays a link.
        assertNull(ImageLinks.imageUrl("https://gyazo.com/..%2f..%2fetc", hosts))
        assertNull(ImageLinks.imageUrl("https://gyazo.com/a.b", hosts))
        assertNull(ImageLinks.imageUrl("https://gyazo.com/" + "a".repeat(100), hosts))
        // What is left once the query and the fragment are cut off is still only the id.
        assertEquals("https://i.gyazo.com/abc.png", ImageLinks.imageUrl("https://gyazo.com/abc?w=1#top", hosts))
    }

    @Test
    fun aHostNobodyAllowedIsNeverFetched() {
        assertNull(ImageLinks.imageUrl("https://evil.example/cat.png", hosts))
        assertNull(ImageLinks.imageUrl("https://twitch.tv/forsen", hosts))
        // "lol.gif" typed in chat parses as a host, and a host is not a picture.
        assertNull(ImageLinks.imageUrl("https://lol.gif", hosts))
    }

    @Test
    fun anAllowedHostCoversItsSubdomains() {
        assertEquals("https://i.gyazo.com/a.png", ImageLinks.imageUrl("https://i.gyazo.com/a.png", listOf("gyazo.com")))
        // ...but not a host that merely ends in the same letters.
        assertNull(ImageLinks.imageUrl("https://notgyazo.com/a.png", listOf("gyazo.com")))
    }

    @Test
    fun anImgurOrGyazoPageIsTurnedIntoThePictureItShows() {
        assertEquals("https://i.imgur.com/AbC123.png", ImageLinks.imageUrl("https://imgur.com/AbC123", hosts))
        assertEquals("https://i.gyazo.com/deadbeef.png", ImageLinks.imageUrl("https://gyazo.com/deadbeef", hosts))
    }

    @Test
    fun anAlbumIsMoreThanOnePictureAndStaysALink() {
        assertNull(ImageLinks.imageUrl("https://imgur.com/a/AbC123", hosts))
        assertNull(ImageLinks.imageUrl("https://imgur.com/gallery/AbC123", hosts))
    }

    @Test
    fun everyPictureInAMessageIsTakenOut() {
        val (rest, urls) = ImageLinks.split(
            listOf(
                Segment.Text("look "),
                link("https://i.imgur.com/one.png"),
                Segment.Text(" and "),
                link("https://i.imgur.com/two.png"),
                Segment.Text(" lol"),
            ),
            hosts,
        )
        assertEquals(listOf("https://i.imgur.com/one.png", "https://i.imgur.com/two.png"), urls)
        assertEquals(listOf(Segment.Text("look "), Segment.Text("and "), Segment.Text("lol")), rest)
    }

    @Test
    fun twoPicturesNextToEachOtherAreBothTakenOut() {
        val (rest, urls) = ImageLinks.split(
            listOf(link("https://i.nuuls.com/a.png"), Segment.Text(" "), link("https://gyazo.com/b")),
            hosts,
        )
        assertEquals(listOf("https://i.nuuls.com/a.png", "https://i.gyazo.com/b.png"), urls)
        assertEquals(emptyList<Segment>(), rest)
    }

    @Test
    fun aPictureAtTheEndTakesItsSpaceWithIt() {
        val (rest, urls) = ImageLinks.split(
            listOf(Segment.Text("look at "), link("https://i.imgur.com/one.png")),
            hosts,
        )
        assertEquals(1, urls.size)
        assertEquals(listOf(Segment.Text("look at")), rest)
    }

    @Test
    fun aLinkThatIsNotAPictureIsLeftWhereItIs() {
        val segments = listOf(Segment.Text("see "), link("https://twitch.tv/forsen"))
        val (rest, urls) = ImageLinks.split(segments, hosts)
        assertEquals(emptyList<String>(), urls)
        assertEquals(segments, rest)
    }

    @Test
    fun anEmptyListOfHostsShowsNothing() {
        val segments = listOf(link("https://i.imgur.com/one.png"))
        val (rest, urls) = ImageLinks.split(segments, emptyList())
        assertEquals(emptyList<String>(), urls)
        assertEquals(segments, rest)
    }

    @Test
    fun aWholeUrlPastedAsAHostBecomesTheHost() {
        assertEquals("imgur.com", ImageLinks.cleanHost("https://www.Imgur.com/some/path?x=1"))
        assertEquals("i.nuuls.com", ImageLinks.cleanHost("  i.nuuls.com  "))
    }
}
