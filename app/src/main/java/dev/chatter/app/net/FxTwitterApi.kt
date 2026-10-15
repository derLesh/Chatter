package dev.chatter.app.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient

/**
 * FxTwitter's public API (github.com/FixTweet/FxTwitter), which reads a post on X without an
 * account. X itself has no free API for it.
 */
class FxTwitterApi(private val http: OkHttpClient) {
    /** [id] must be digits only; see [dev.chatter.app.chat.TweetLinks.tweetId]. */
    suspend fun tweet(id: String): FxTweetResponse = http.getJson("https://api.fxtwitter.com/status/$id")
}

@Serializable
data class FxTweetResponse(val code: Int = 0, val tweet: FxTweet? = null)

@Serializable
data class FxTweet(
    val url: String = "",
    val text: String = "",
    @SerialName("raw_text") val rawText: FxRawText? = null,
    val author: FxAuthor = FxAuthor(),
    val replies: Long = 0,
    val retweets: Long = 0,
    val likes: Long = 0,
    /** Seconds since the epoch. */
    @SerialName("created_timestamp") val createdTimestamp: Long = 0,
    @SerialName("possibly_sensitive") val possiblySensitive: Boolean = false,
    val media: FxMedia? = null,
)

@Serializable
data class FxRawText(val facets: List<FxFacet> = emptyList())

/** A link, mention or attachment in the text; [original] is how it is written there. */
@Serializable
data class FxFacet(val type: String = "", val original: String = "")

@Serializable
data class FxAuthor(
    val name: String = "",
    @SerialName("screen_name") val screenName: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class FxMedia(val all: List<FxMediaItem> = emptyList())

@Serializable
data class FxMediaItem(
    /** "photo", "video" or "gif". */
    val type: String = "",
    val url: String = "",
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    val width: Int = 0,
    val height: Int = 0,
)
