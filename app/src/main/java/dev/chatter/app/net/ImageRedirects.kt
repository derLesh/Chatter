package dev.chatter.app.net

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Follows a picture's redirects only on the host it was asked from, for a client with redirects
 * turned off.
 *
 * Where a picture may come from is checked on its URL before it is asked for ([TrustedImages],
 * the user's image hosts, a post's site), and a redirect to another host would get around that.
 * None of them needs one: only Imgur redirects, a removed picture to i.imgur.com/removed.png. A
 * redirect that is not followed is handed on as it came, which fails the picture like a 404.
 */
object ImageRedirects : Interceptor {
    /** More than any host needs; a loop ends here. */
    private const val MAX_HOPS = 5

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var response = chain.proceed(request)
        repeat(MAX_HOPS) {
            if (!response.isRedirect) return response
            val next = response.header("Location")?.let { request.url.resolve(it) }
            if (next == null || !allowed(request.url, next)) return response
            response.close()
            request = request.newBuilder().url(next).build()
            response = chain.proceed(request)
        }
        return response
    }

    /** Same host, never from https to http, and no user name or password. Internal for tests. */
    internal fun allowed(from: HttpUrl, to: HttpUrl): Boolean =
        to.host == from.host &&
            (to.isHttps || !from.isHttps) &&
            to.username.isEmpty() && to.password.isEmpty()
}
