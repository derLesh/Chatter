package me.lesh.chatter.auth

import java.net.URI
import java.net.URLDecoder

/**
 * Where Twitch sends the answer of 7TV's login: 7TV's website, which the login WebView never
 * loads. The app finishes the login itself; see [me.lesh.chatter.net.SevenTvApi.finishLogin].
 */
object SevenTvLogin {
    /** Whether [url] is `https://7tv.app/login/callback?…`, exactly that host and path. */
    fun isCallback(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(HOST, ignoreCase = true) &&
            uri.port == -1 &&
            uri.rawUserInfo == null &&
            uri.rawPath == PATH
    }

    /**
     * Whether [url] is Twitch's authorization page, where 7TV's login sends the WebView first. That
     * first load never passes the WebView's check of where a page may go, so it is checked here.
     */
    fun isTwitchAuthorize(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(TWITCH_ID_HOST, ignoreCase = true) &&
            uri.port == -1 &&
            uri.rawUserInfo == null &&
            uri.rawPath == AUTHORIZE_PATH
    }

    /** The code in the callback [url], or null where Twitch reports an error, like a declined login. */
    fun code(url: String): String? {
        if (!isCallback(url)) return null
        val query = URI(url).rawQuery ?: return null
        val params = query.split('&').filter { it.isNotEmpty() }.associate { part ->
            decode(part.substringBefore('=')) to decode(part.substringAfter('=', ""))
        }
        return params["code"]?.takeIf { it.isNotEmpty() && "error" !in params }
    }

    private const val HOST = "7tv.app"
    private const val PATH = "/login/callback"
    private const val TWITCH_ID_HOST = "id.twitch.tv"
    private const val AUTHORIZE_PATH = "/oauth2/authorize"

    private fun decode(text: String): String = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
}
