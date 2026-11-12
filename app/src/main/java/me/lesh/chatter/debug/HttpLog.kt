package me.lesh.chatter.debug

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * One log line per request: method, where to, the answer and how long it took. Never the query or
 * the body, and the path only for APIs whose paths name nothing but the endpoint.
 */
object HttpLog : Interceptor {
    private const val TAG = "Http"

    /** Twitch's and Chatter's own paths are endpoints; a user or channel is only ever in the query. */
    private val PATH_IS_ENDPOINT = setOf("api.twitch.tv", "api.lesh.me", "api.github.com")

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val what = "${request.method} ${shown(request.url)}"
        val started = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            AppLog.w(TAG, "$what failed after ${millisSince(started)} ms", e)
            throw e
        }
        val cached = if (response.networkResponse == null && response.cacheResponse != null) " (cache)" else ""
        val line = "$what → ${response.code}$cached in ${millisSince(started)} ms"
        if (response.isSuccessful || response.code == 304) AppLog.d(TAG, line) else AppLog.w(TAG, line)
        return response
    }

    /** Internal for tests. */
    internal fun shown(url: HttpUrl): String =
        if (url.host in PATH_IS_ENDPOINT) url.host + url.encodedPath else url.host

    private fun millisSince(started: Long) = (System.nanoTime() - started) / 1_000_000
}
