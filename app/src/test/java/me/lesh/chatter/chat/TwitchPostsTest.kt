package me.lesh.chatter.chat

import me.lesh.chatter.net.AppJson
import me.lesh.chatter.net.HelixClip
import me.lesh.chatter.net.HelixList
import me.lesh.chatter.net.HelixStream
import me.lesh.chatter.net.HelixUser
import me.lesh.chatter.net.HelixVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchPostsTest {
    private fun key(url: String) = PostLinks.find(url, setOf(PostSite.Twitch))?.key

    @Test
    fun aChannelLinkIsItsLoginInLowerCase() {
        for (url in listOf(
            "https://www.twitch.tv/Chatter_App",
            "https://twitch.tv/chatter_app/",
            "https://m.twitch.tv/chatter_app?sr=a",
            "http://twitch.tv/chatter_app",
        )) assertEquals(url, "channel/chatter_app", key(url))
    }

    @Test
    fun everyKindOfClipLinkIsItsSlug() {
        for (url in listOf(
            "https://clips.twitch.tv/$SLUG",
            "https://clips.twitch.tv/$SLUG?tt_medium=clips_api",
            "https://clips.twitch.tv/embed?clip=$SLUG&parent=example.com",
            "https://www.twitch.tv/chatter_app/clip/$SLUG",
            "https://m.twitch.tv/chatter_app/clip/$SLUG?filter=clips",
            "https://m.twitch.tv/clip/$SLUG",
        )) assertEquals(url, "clip/$SLUG", key(url))
    }

    @Test
    fun everyKindOfVideoLinkIsItsNumber() {
        for (url in listOf(
            "https://www.twitch.tv/videos/2281234567",
            "https://www.twitch.tv/videos/2281234567?t=1h2m3s",
            "https://m.twitch.tv/videos/2281234567",
            "https://www.twitch.tv/chatter_app/video/2281234567",
            "https://www.twitch.tv/chatter_app/v/2281234567",
        )) assertEquals(url, "video/2281234567", key(url))
    }

    @Test
    fun twitchsOwnPagesAndEverythingElseStayLinks() {
        for (url in listOf(
            "https://www.twitch.tv/",
            "https://www.twitch.tv/directory",
            "https://www.twitch.tv/settings",
            "https://www.twitch.tv/subscriptions",
            "https://www.twitch.tv/videos",
            "https://www.twitch.tv/chatter_app/videos",
            "https://www.twitch.tv/chatter_app/schedule",
            "https://www.twitch.tv/popout/chatter_app/chat",
            "https://www.twitch.tv/directory/category/just-chatting",
            "https://clips.twitch.tv/",
            "https://clips.twitch.tv/embed",
            "https://dashboard.twitch.tv/chatter_app",
            "https://api.twitch.tv/helix/users",
        )) assertNull(url, key(url))
    }

    @Test
    fun onlyWhatTwitchAllowsGetsIntoTheQuery() {
        assertNull(key("https://www.twitch.tv/videos/12ab"))
        assertNull(key("https://www.twitch.tv/chat-ter"))
        assertNull(key("https://www.twitch.tv/a_name_far_too_long_for_twitch"))
        assertNull(key("https://clips.twitch.tv/embed?clip=..%2F..%2Fusers"))
        assertNull(key("https://clips.twitch.tv/a%26id%3D1"))
    }

    @Test
    fun aHostDressedUpAsTwitchIsStillItself() {
        assertNull(key("https://twitch.tv.example.com/chatter_app"))
        assertNull(key("https://eviltwitch.tv/chatter_app"))
        assertNull(key("https://twitch.tv@example.com/chatter_app"))
        assertNull(key("https://user:pass@twitch.tv/chatter_app"))
    }

    @Test
    fun aLiveChannelShowsItsStreamWithViewersAndCategory() {
        val user = AppJson.decodeFromString<HelixList<HelixUser>>(USER).data.single()
        val stream = AppJson.decodeFromString<HelixList<HelixStream>>(STREAM).data.single()
        val post = TwitchPosts.channel(user, stream, followers = 1234)
        assertEquals("Chatter_App", post.name)
        assertEquals("", post.handle)
        assertEquals("https://static-cdn.jtvnw.net/jtv_user_pictures/chatter-profile_image-300x300.png", post.avatarUrl)
        assertEquals("Building a chat client", post.text)
        assertEquals(4321, post.viewers)
        assertEquals(1234, post.followers)
        assertEquals("Software and Game Development", post.category)
        assertEquals(0, post.postedAt)
        assertTrue(post.profile)
        assertTrue(post.live)
        assertNull(post.media)
    }

    @Test
    fun anOfflineChannelShowsItsDescription() {
        val user = AppJson.decodeFromString<HelixList<HelixUser>>(USER).data.single()
        val post = TwitchPosts.channel(user, live = null, followers = 0)
        assertEquals("A chat client for Twitch.", post.text)
        assertEquals(0, post.viewers)
        assertEquals("", post.category)
        assertTrue(post.profile)
        assertFalse(post.live)
    }

    @Test
    fun aNameInOtherLettersKeepsTheLoginBeside() {
        val user = HelixUser(id = "1", login = "chatter_app", displayName = "チャッター")
        assertEquals("chatter_app", TwitchPosts.channel(user, null, 0).handle)
    }

    @Test
    fun aClipShowsItsTitleStillViewsAndLength() {
        val clip = AppJson.decodeFromString<HelixList<HelixClip>>(CLIP).data.single()
        val post = TwitchPosts.clip(clip, avatarUrl = null)
        assertEquals("Chatter_App", post.name)
        assertEquals("The chat goes wild", post.text)
        assertEquals(98765, post.views)
        assertEquals(PostLabel.Clip, post.label)
        assertEquals(1_763_208_000_000, post.postedAt)
        val media = post.media!!
        assertEquals("https://static-cdn.jtvnw.net/twitch-clips-thumbnails-prod/$SLUG/abc/preview-480x272.jpg", media.pictureUrl)
        assertEquals(29, media.lengthSeconds)
        assertTrue(media.video)
    }

    @Test
    fun anOlderClipKeepsItsStillFromTheOldHost() {
        val clip = HelixClip(id = SLUG, thumbnailUrl = "https://clips-media-assets2.twitch.tv/157589949-preview-480x272.jpg")
        assertEquals(clip.thumbnailUrl, TwitchPosts.clip(clip, null).media?.pictureUrl)
    }

    @Test
    fun aVideoShowsItsTitleStillViewsAndLength() {
        val video = AppJson.decodeFromString<HelixList<HelixVideo>>(VIDEO).data.single()
        val post = TwitchPosts.video(video, avatarUrl = "https://static-cdn.jtvnw.net/jtv_user_pictures/a.png")
        assertEquals("Chatter_App", post.name)
        assertEquals("Long stream", post.text)
        assertEquals(555, post.views)
        assertEquals(PostLabel.Vod, post.label)
        assertEquals("https://static-cdn.jtvnw.net/cf_vods/d1m7jfoe9zdc1j/abc/thumb/thumb0-640x360.jpg", post.media?.pictureUrl)
        assertEquals(3 * 3600 + 8 * 60 + 33, post.media?.lengthSeconds)
    }

    @Test
    fun aHighlightAndAnUploadAreNotCalledVods() {
        assertEquals(PostLabel.Highlight, TwitchPosts.video(HelixVideo(id = "1", type = "highlight"), null).label)
        assertEquals(PostLabel.Upload, TwitchPosts.video(HelixVideo(id = "1", type = "upload"), null).label)
    }

    @Test
    fun aVideoStillBeingRecordedHasNoStill() {
        val processing = "https://vod-secure.twitch.tv/_404/404_processing_%{width}x%{height}.png"
        assertNull(TwitchPosts.video(HelixVideo(id = "1", thumbnailUrl = processing), null).media)
        assertNull(TwitchPosts.video(HelixVideo(id = "1", thumbnailUrl = ""), null).media)
    }

    @Test
    fun aPictureFromElsewhereIsLeftOut() {
        val user = HelixUser(id = "1", login = "a", displayName = "a", profileImageUrl = "https://example.com/a.png")
        assertNull(TwitchPosts.channel(user, null, 0).avatarUrl)
        assertNull(TwitchPosts.picture("http://static-cdn.jtvnw.net/a.png"))
    }

    @Test
    fun videoLengthsAreReadInSeconds() {
        assertEquals(33, TwitchPosts.videoSeconds("33s"))
        assertEquals(8 * 60 + 33, TwitchPosts.videoSeconds("8m33s"))
        assertEquals(3600, TwitchPosts.videoSeconds("1h0m0s"))
        assertEquals(0, TwitchPosts.videoSeconds(""))
        assertEquals(0, TwitchPosts.videoSeconds("soon"))
    }

    private companion object {
        const val SLUG = "AwkwardHelplessSalamanderSwiftRage-a1B2c3D4e5F6g7H8"

        const val USER = """{"data":[{"id":"141981764","login":"chatter_app","display_name":"Chatter_App",
            "type":"","broadcaster_type":"partner","description":"A chat client for Twitch. ",
            "profile_image_url":"https://static-cdn.jtvnw.net/jtv_user_pictures/chatter-profile_image-300x300.png",
            "offline_image_url":"","view_count":0,"created_at":"2016-12-14T20:32:28Z"}]}"""

        const val STREAM = """{"data":[{"id":"1","user_id":"141981764","user_login":"chatter_app",
            "user_name":"Chatter_App","game_id":"1469308723","game_name":"Software and Game Development",
            "type":"live","title":"Building a chat client","viewer_count":4321,
            "started_at":"2026-11-15T10:00:00Z","language":"en",
            "thumbnail_url":"https://static-cdn.jtvnw.net/previews-ttv/live_user_chatter_app-{width}x{height}.jpg",
            "tags":["English"],"is_mature":false}],"pagination":{}}"""

        const val CLIP = """{"data":[{"id":"$SLUG","url":"https://clips.twitch.tv/$SLUG",
            "embed_url":"https://clips.twitch.tv/embed?clip=$SLUG","broadcaster_id":"141981764",
            "broadcaster_name":"Chatter_App","creator_id":"2","creator_name":"someone","video_id":"",
            "game_id":"1","language":"en","title":"The chat goes wild","view_count":98765,
            "created_at":"2025-11-15T12:00:00Z",
            "thumbnail_url":"https://static-cdn.jtvnw.net/twitch-clips-thumbnails-prod/$SLUG/abc/preview-480x272.jpg",
            "duration":28.9,"vod_offset":null,"is_featured":false}],"pagination":{}}"""

        const val VIDEO = """{"data":[{"id":"2281234567","stream_id":"1","user_id":"141981764",
            "user_login":"chatter_app","user_name":"Chatter_App","title":"Long stream","description":"",
            "created_at":"2026-11-14T18:00:00Z","published_at":"2026-11-14T18:00:00Z",
            "url":"https://www.twitch.tv/videos/2281234567",
            "thumbnail_url":"https://static-cdn.jtvnw.net/cf_vods/d1m7jfoe9zdc1j/abc/thumb/thumb0-%{width}x%{height}.jpg",
            "viewable":"public","view_count":555,"language":"en","type":"archive","duration":"3h8m33s",
            "muted_segments":null}],"pagination":{}}"""
    }
}
