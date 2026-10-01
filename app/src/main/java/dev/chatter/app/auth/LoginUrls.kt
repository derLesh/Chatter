package dev.chatter.app.auth

import java.net.URI
import java.net.URLDecoder

/**
 * Which addresses belong to the login, read with java.net.URI so the rules can be tested without
 * Android: the login page is where the user types their Twitch password, so what it may load and
 * what counts as Twitch's answer are decided here and nowhere else.
 */
object LoginUrls {
    /**
     * Whether [url] is Twitch's answer, `http://localhost#access_token=…`. Scheme, host and port
     * have to match exactly: a prefix check also lets `http://localhost.example.com` or
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
     * The parameters of Twitch's answer. The implicit flow only ever answers in the fragment; a
     * query is somebody else's idea and is not read.
     */
    fun answer(url: String): Map<String, String> {
        val fragment = parse(url)?.rawFragment ?: return emptyMap()
        return fragment.split('&').filter { it.isNotEmpty() }.associate { part ->
            decode(part.substringBefore('=')) to decode(part.substringAfter('=', ""))
        }
    }

    /**
     * Whether the login page may go to [url] inside the app: https on Twitch's own hosts. Any
     * other site — help pages, terms, somebody else's sign-in — opens in the user's browser,
     * which shows the address the WebView does not.
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
