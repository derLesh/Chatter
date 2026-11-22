package me.lesh.chatter.chat

import android.os.SystemClock
import android.util.LruCache
import me.lesh.chatter.net.HttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.io.IOException

/**
 * Posts for their cards, each fetched once while the app runs, from the [PostSource] of its site.
 * Asked only when a card comes on screen, so a channel running in the background never fetches any.
 */
class PostRepository(sources: List<PostSource>, private val scope: CoroutineScope) {
    private val sources = sources.associateBy { it.site }

    /**
     * A missing post is remembered too; [Known] holds the null that [LruCache] cannot. [until]:
     * [SystemClock.elapsedRealtime] after which it is asked again.
     */
    private class Known(val post: Post?, val until: Long) {
        val fresh get() = SystemClock.elapsedRealtime() < until
    }

    private val known = LruCache<PostLink, Known>(CACHE_SIZE)
    private val loading = HashMap<PostLink, Deferred<Post?>>()

    /** The post [link] points to if it was already fetched, without waiting, even if it is due to be asked again. */
    fun known(link: PostLink): Post? = known.get(cacheKey(link))?.post

    /** Whether [post] would answer without asking. */
    fun isFresh(link: PostLink): Boolean = known.get(cacheKey(link))?.fresh == true

    /** The post [link] points to, or null if it is deleted, private, or could not be read right now. */
    suspend fun post(link: PostLink): Post? {
        val key = cacheKey(link)
        known.get(key)?.takeIf { it.fresh }?.let { return it.post }
        val source = sources[link.site] ?: return null
        // The same post is often on screen twice, in the chat and in a user card.
        val load = synchronized(loading) { loading.getOrPut(key) { scope.async { fetch(source, key) } } }
        return load.await()
    }

    /** Two links to one post share an entry, whatever else their URLs say. */
    private fun cacheKey(link: PostLink) = link.copy(url = "")

    private suspend fun fetch(source: PostSource, key: PostLink): Post? = try {
        source.fetch(key.key).also { remember(source, key, it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        // A site that is down or busy says nothing about the post: asked again soon. Any other
        // error means the post is gone or private.
        val passing = e.code == 429 || e.code in 500..599
        null.also { remember(source, key, null, freshFor = if (passing) RETRY_AFTER_MS else source.freshFor(key.key)) }
    } catch (e: IOException) {
        // Offline for a moment; the next time the card is shown it asks again.
        null
    } catch (e: IllegalArgumentException) {
        // Not the JSON expected, like the HTML page FxTwitter answers a deleted post with.
        null.also { remember(source, key, null) }
    } finally {
        synchronized(loading) { loading.remove(key) }
    }

    private fun remember(source: PostSource, key: PostLink, post: Post?, freshFor: Long = source.freshFor(key.key)) {
        val until = if (freshFor == Long.MAX_VALUE) Long.MAX_VALUE else SystemClock.elapsedRealtime() + freshFor
        known.put(key, Known(post, until))
    }

    private companion object {
        const val CACHE_SIZE = 200

        /** How long a site's failure holds before the post is asked for again. */
        const val RETRY_AFTER_MS = 60_000L
    }
}
