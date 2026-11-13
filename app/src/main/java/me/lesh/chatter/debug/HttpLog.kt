package me.lesh.chatter.debug

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * One log line per request: method, where to, the answer, how it came (network, cache, checked
 * against the cache), protocol, size, Twitch's rate limit and how long it took. Never a body or a
 * header with a login. The private log names only the host, and the path for APIs whose paths name
 * nothing but the endpoint; the full log has the whole link.
 *
 * [level] for a successful answer: images go in as [AppLog.Level.Verbose], since a busy chat
 * loads many a minute.
 */
class HttpLog(private val tag: String, private val level: AppLog.Level = AppLog.Level.Debug) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val what = "${request.method} ${AppLog.hidden(full(request.url), instead = shown(request.url))}"
        val started = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            AppLog.w(tag, "$what failed after ${millisSince(started)} ms", e)
            throw e
        }
        val line = "$what → ${response.code}${details(response)} in ${millisSince(started)} ms"
        when {
            response.isSuccessful || response.code == 304 || response.code == 101 -> log(level, line)
            else -> AppLog.w(tag, line)
        }
        return response
    }

    private fun log(level: AppLog.Level, line: String) = when (level) {
        AppLog.Level.Verbose -> AppLog.v(tag, line)
        AppLog.Level.Debug -> AppLog.d(tag, line)
        AppLog.Level.Info -> AppLog.i(tag, line)
        AppLog.Level.Warn -> AppLog.w(tag, line)
        AppLog.Level.Error -> AppLog.e(tag, line)
    }

    companion object {
        /** Twitch's and Chatter's own paths are endpoints; a user or channel is only ever in the query. */
        private val PATH_IS_ENDPOINT = setOf("api.twitch.tv", "api.lesh.me", "api.github.com")

        /** Query values that are keys: kappa.lol's deletes the picture uploaded with it. */
        private val SECRET_PARAMS = setOf("key")

        /** Paths that end in a key: Imgur deletes a picture at /3/image/<deletehash>. */
        private val SECRET_PATH_ENDS = mapOf("api.imgur.com" to listOf("3", "image"))

        /** The whole link for the full log, with keys blanked. Internal for tests. */
        internal fun full(url: HttpUrl): String {
            val builder = url.newBuilder()
            url.queryParameterNames.filter { it in SECRET_PARAMS }.forEach { builder.setQueryParameter(it, "…") }
            val prefix = SECRET_PATH_ENDS[url.host]
            if (prefix != null && url.pathSegments.size == prefix.size + 1 && url.pathSegments.take(prefix.size) == prefix) {
                builder.setPathSegment(prefix.size, "…")
            }
            return builder.build().toString()
        }

        /** Internal for tests. */
        internal fun shown(url: HttpUrl): String =
            if (url.host in PATH_IS_ENDPOINT) url.host + url.encodedPath else url.host

        /** ", cache, h2, 12 kB, 798 left" — whatever the answer says about itself. Internal for tests. */
        internal fun details(response: Response): String {
            val source = when {
                response.networkResponse == null && response.cacheResponse != null -> "cache"
                response.networkResponse?.code == 304 && response.cacheResponse != null -> "unchanged, from cache"
                else -> null
            }
            val size = response.body.contentLength().takeIf { it >= 0 }?.let(::bytes)
            // Twitch counts requests per minute; low numbers explain refused requests.
            val rateLimit = response.header("Ratelimit-Remaining")?.let { "$it of ${response.header("Ratelimit-Limit") ?: "?"} left" }
            val retry = response.header("Retry-After")?.let { "retry after $it s" }
            val parts = listOfNotNull(source, response.protocol.toString(), size, rateLimit, retry)
            return " (${parts.joinToString()})"
        }

        private fun bytes(count: Long): String = when {
            count < 1_024 -> "$count B"
            count < 1_048_576 -> "${count / 1_024} kB"
            else -> "%.1f MB".format(java.util.Locale.ROOT, count / 1_048_576.0)
        }
    }

    private fun millisSince(started: Long) = (System.nanoTime() - started) / 1_000_000
}
