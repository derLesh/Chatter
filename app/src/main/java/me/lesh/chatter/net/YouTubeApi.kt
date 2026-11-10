package me.lesh.chatter.net

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * What YouTube says about a video, without an account or a key. The official Data API needs a key
 * tied to a Google project, so the views come from the player YouTube's own website asks.
 */
class YouTubeApi(private val http: OkHttpClient) {
    /**
     * The title, channel and views of [id], titles in [language] where the channel translated them.
     * [id] must be a video id; see [me.lesh.chatter.chat.YouTubeVideos.key].
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun player(id: String, language: String): YtPlayerResponse {
        val body = buildJsonObject {
            put("videoId", id)
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB")
                    put("clientVersion", WEB_CLIENT_VERSION)
                    put("hl", language)
                }
            }
        }
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return http.fetchDecoding(request) { AppJson.decodeFromStream<YtPlayerResponse>(it) }
    }

    /** Title and channel only, from YouTube's embed API. Answers 401 for a private video, 400 for none. */
    suspend fun oembed(id: String): YtOembed {
        val url = "https://www.youtube.com/oembed".toHttpUrl().newBuilder()
            .addQueryParameter("url", "https://www.youtube.com/watch?v=$id")
            .addQueryParameter("format", "json")
            .build()
        return http.getJson(url.toString())
    }

    private companion object {
        /** YouTube answers older versions too; a much older one may one day be told to update. */
        const val WEB_CLIENT_VERSION = "2.20251001.01.00"
    }
}

@Serializable
data class YtPlayerResponse(
    val playabilityStatus: YtPlayability = YtPlayability(),
    /** Missing for a video that is gone, and when YouTube wants a sign-in first. */
    val videoDetails: YtVideoDetails? = null,
    val microformat: YtMicroformat? = null,
)

/** "OK", "ERROR" for a video that does not exist, "LOGIN_REQUIRED", "UNPLAYABLE" and others. */
@Serializable
data class YtPlayability(val status: String = "")

/** YouTube sends its numbers as strings. */
@Serializable
data class YtVideoDetails(
    val title: String = "",
    val author: String = "",
    val viewCount: String = "",
    /** "0" while it is live. */
    val lengthSeconds: String = "",
)

@Serializable
data class YtMicroformat(val playerMicroformatRenderer: YtMicroformatRenderer? = null)

@Serializable
data class YtMicroformatRenderer(
    /** ISO 8601 with an offset, like "2009-10-24T23:57:33-07:00". */
    val publishDate: String? = null,
    /** False for an age-restricted video. */
    val isFamilySafe: Boolean = true,
)

@Serializable
data class YtOembed(
    val title: String = "",
    @SerialName("author_name") val authorName: String = "",
)
