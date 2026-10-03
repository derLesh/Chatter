package dev.chatter.app.auth

import java.net.URI
import java.net.URLDecoder

/**
 * Which URLs belong to the login. Plain java.net.URI, so the rules are testable without Android.
 */
object LoginUrls {
    /**
     * Whether [url] is Twitch's redirect, `http://localhost#access_token=…`. Scheme, host and port
     * must match exactly; a prefix check would let `http://localhost.example.com` or
     * `http://localhost:8080@example.com` through.
     */
    fun isRedirect(url: String): Boolean {
        val uri = parse(url) ?: return false
        return uri.scheme.equals("http", ignoreCase = true) &&
            uri.host.equals("localhost", ignoreCase = true) &&
            uri.port == -1 &&
            uri.rawUserInfo == null &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
    }

    /**
     * The parameters of Twitch's answer. The implicit flow answers in the fragment only; a query is
     * ignored.
     */
    fun answer(url: String): Map<String, String> {
        val fragment = parse(url)?.rawFragment ?: return emptyMap()
        return fragment.split('&').filter { it.isNotEmpty() }.associate { part ->
            decode(part.substringBefore('=')) to decode(part.substringAfter('=', ""))
        }
    }

    /**
     * Whether the login WebView may load [url]: https on Twitch hosts only. Everything else opens
     * in the browser, which shows the address.
     */
    fun staysInLogin(url: String): Boolean {
        val uri = parse(url) ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.rawUserInfo == null &&
            (host == TWITCH || host.endsWith(".$TWITCH"))
    }

    private const val TWITCH = "twitch.tv"

    private fun parse(url: String): URI? = runCatching { URI(url) }.getOrNull()

    private fun decode(text: String): String = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
}
