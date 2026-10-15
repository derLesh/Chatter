package dev.chatter.app.chat

import android.util.LruCache
import dev.chatter.app.net.HttpException
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

    /** A missing post is remembered too; [Known] holds the null that [LruCache] cannot. */
    private class Known(val post: Post?)

    private val known = LruCache<PostLink, Known>(CACHE_SIZE)
    private val loading = HashMap<PostLink, Deferred<Post?>>()

    /** The post [link] points to if it was already fetched, without waiting. */
    fun known(link: PostLink): Post? = known.get(cacheKey(link))?.post

    /** The post [link] points to, or null if it is deleted, private, or could not be read right now. */
    suspend fun post(link: PostLink): Post? {
        val key = cacheKey(link)
        known.get(key)?.let { return it.post }
        val source = sources[link.site] ?: return null
        // The same post is often on screen twice, in the chat and in a user card.
        val load = synchronized(loading) { loading.getOrPut(key) { scope.async { fetch(source, key) } } }
        return load.await()
    }

    /** Two links to one post share an entry, whatever else their URLs say. */
    private fun cacheKey(link: PostLink) = link.copy(url = "")

    private suspend fun fetch(source: PostSource, key: PostLink): Post? = try {
        source.fetch(key.key).also { known.put(key, Known(it)) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        null.also { known.put(key, Known(null)) }
    } catch (e: IOException) {
        // Offline for a moment; the next time the card is shown it asks again.
        null
    } catch (e: IllegalArgumentException) {
        // Not the JSON expected, like the HTML page FxTwitter answers a deleted post with.
        null.also { known.put(key, Known(null)) }
    } finally {
        synchronized(loading) { loading.remove(key) }
    }

    private companion object {
        const val CACHE_SIZE = 200
    }
}
