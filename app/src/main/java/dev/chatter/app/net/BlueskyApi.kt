package dev.chatter.app.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** Bluesky's public AppView, which reads a post without an account. */
class BlueskyApi(private val http: OkHttpClient) {
    /**
     * The post [rkey] of [actor], a handle or DID; see [dev.chatter.app.chat.BlueskyPosts.key].
     * Answers 400 for a post that does not exist.
     */
    suspend fun post(actor: String, rkey: String): BskyThreadResponse {
        val url = "https://public.api.bsky.app/xrpc/app.bsky.feed.getPostThread".toHttpUrl().newBuilder()
            .addQueryParameter("uri", "at://$actor/app.bsky.feed.post/$rkey")
            // The post alone, without its replies or the posts it answers.
            .addQueryParameter("depth", "0")
            .addQueryParameter("parentHeight", "0")
            .build()
        return http.getJson(url.toString())
    }
}

@Serializable
data class BskyThreadResponse(val thread: BskyThread? = null)

/** A blocked or deleted post comes without [post]. */
@Serializable
data class BskyThread(val post: BskyPost? = null)

@Serializable
data class BskyPost(
    val author: BskyAuthor = BskyAuthor(),
    val record: BskyRecord = BskyRecord(),
    val embed: BskyEmbed? = null,
    val replyCount: Long = 0,
    val repostCount: Long = 0,
    val likeCount: Long = 0,
    /** From moderation services and the author's own; "porn", "nudity" and the like. */
    val labels: List<BskyLabel> = emptyList(),
)

@Serializable
data class BskyAuthor(val handle: String = "", val displayName: String? = null, val avatar: String? = null)

@Serializable
data class BskyRecord(val text: String = "", val createdAt: String = "")

@Serializable
data class BskyLabel(@SerialName("val") val value: String = "")

/** Every kind of attachment in one shape; [type] says which fields are filled. */
@Serializable
data class BskyEmbed(
    @SerialName("\$type") val type: String = "",
    val images: List<BskyImage> = emptyList(),
    /** A video's still. */
    val thumbnail: String? = null,
    val aspectRatio: BskyAspectRatio? = null,
    /** A link's preview. */
    val external: BskyExternal? = null,
    /** The pictures or video next to a quoted post. */
    val media: BskyEmbed? = null,
)

@Serializable
data class BskyImage(val thumb: String = "", val aspectRatio: BskyAspectRatio? = null)

@Serializable
data class BskyAspectRatio(val width: Int = 0, val height: Int = 0)

@Serializable
data class BskyExternal(val thumb: String? = null)
