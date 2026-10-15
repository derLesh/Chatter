package dev.chatter.app.chat

import android.util.LruCache
import dev.chatter.app.net.FxTweet
import dev.chatter.app.net.FxTwitterApi
import dev.chatter.app.net.HttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** A post on X as its card shows it. */
data class Tweet(
    val name: String,
    val handle: String,
    val avatarUrl: String?,
    val text: String,
    val replies: Long,
    val reposts: Long,
    val likes: Long,
    /** Milliseconds since the epoch. */
    val postedAt: Long,
    /** Marked by X as possibly sensitive; its picture is blurred if the user wants that. */
    val sensitive: Boolean,
    val media: TweetMedia?,
) {
    companion object {
        /**
         * The card for [fx], or null without an author. Pictures are only taken from X's own image
         * host, so a changed API cannot make the app fetch from anywhere else.
         */
        fun from(fx: FxTweet): Tweet? {
            if (fx.author.screenName.isBlank()) return null
            // Older posts still end in the t.co link of their attachment, which the card shows.
            val attachmentLinks = fx.rawText?.facets.orEmpty().filter { it.type == "media" && it.original.isNotEmpty() }
            val text = attachmentLinks.fold(fx.text) { text, facet -> text.replace(facet.original, "") }.trim()
            return Tweet(
                name = fx.author.name.ifBlank { fx.author.screenName },
                handle = fx.author.screenName,
                avatarUrl = twimg(fx.author.avatarUrl)?.toString(),
                text = text,
                replies = fx.replies,
                reposts = fx.retweets,
                likes = fx.likes,
                postedAt = fx.createdTimestamp * 1000,
                sensitive = fx.possiblySensitive,
                media = media(fx),
            )
        }

        private fun media(fx: FxTweet): TweetMedia? {
            val all = fx.media?.all.orEmpty()
            val first = all.firstOrNull() ?: return null
            val video = first.type != "photo"
            val picture = if (video) twimg(first.thumbnailUrl)
            // The original can be several megabytes; "small" is 680 pixels wide at most.
            else twimg(first.url)?.newBuilder()?.setQueryParameter("name", "small")?.build()
            return TweetMedia(
                pictureUrl = picture?.toString() ?: return null,
                video = video,
                aspectRatio = if (first.width > 0 && first.height > 0) first.width.toFloat() / first.height else null,
                more = all.size - 1,
            )
        }

        private fun twimg(raw: String?): HttpUrl? {
            val parsed = raw?.toHttpUrlOrNull() ?: return null
            if (!parsed.isHttps || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
            return parsed.takeIf { it.host == "twimg.com" || it.host.endsWith(".twimg.com") }
        }
    }
}

/** The first picture of a post, or a still of its video. [more]: how many other attachments it has. */
data class TweetMedia(val pictureUrl: String, val video: Boolean, val aspectRatio: Float?, val more: Int)

/**
 * Posts on X for their cards, each fetched once while the app runs. Asked only when a card comes on
 * screen, so a channel running in the background never fetches any.
 */
class TweetRepository(private val api: FxTwitterApi, private val scope: CoroutineScope) {
    /** A missing post is remembered too; [Known] holds the null that [LruCache] cannot. */
    private class Known(val tweet: Tweet?)

    private val known = LruCache<String, Known>(CACHE_SIZE)
    private val loading = HashMap<String, Deferred<Tweet?>>()

    /** The post [id] if it was already fetched, without waiting. */
    fun known(id: String): Tweet? = known.get(id)?.tweet

    /** The post [id], or null if it is deleted, private, or could not be read right now. */
    suspend fun tweet(id: String): Tweet? {
        known.get(id)?.let { return it.tweet }
        // The same post is often on screen twice, in the chat and in a user card.
        val load = synchronized(loading) { loading.getOrPut(id) { scope.async { fetch(id) } } }
        return load.await()
    }

    private suspend fun fetch(id: String): Tweet? = try {
        api.tweet(id).tweet?.let(Tweet::from).also { known.put(id, Known(it)) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        null.also { known.put(id, Known(null)) }
    } catch (e: IOException) {
        // Offline for a moment; the next time the card is shown it asks again.
        null
    } catch (e: IllegalArgumentException) {
        // FxTwitter answers a deleted post with an HTML page.
        null.also { known.put(id, Known(null)) }
    } finally {
        synchronized(loading) { loading.remove(id) }
    }

    private companion object {
        const val CACHE_SIZE = 200
    }
}
