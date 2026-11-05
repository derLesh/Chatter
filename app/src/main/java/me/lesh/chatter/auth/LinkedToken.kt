package me.lesh.chatter.auth

import java.net.URI
import java.net.URLDecoder

/**
 * Reads the token the Chatter website hands over after its Twitch login, `chatter://login?token=…`.
 * The website opens it as an intent link pinned to Chatter's package, so other apps do not get it;
 * any page can still send one, which is why the app asks before logging in.
 */
object LinkedToken {
    fun read(url: String?): String? {
        val uri = url?.let { runCatching { URI(it) }.getOrNull() } ?: return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true) || !uri.host.equals(HOST, ignoreCase = true)) return null
        val token = uri.rawQuery.orEmpty().split('&')
            .firstOrNull { it.startsWith("token=") }
            ?.substringAfter('=')
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        return token?.takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }
    }

    private const val SCHEME = "chatter"
    private const val HOST = "login"
}
