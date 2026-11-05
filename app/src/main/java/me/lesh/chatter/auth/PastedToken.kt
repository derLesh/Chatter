package me.lesh.chatter.auth

/**
 * Reads a token pasted or scanned by hand, the fallback for when Twitch's login page does not load
 * in the app. Takes the bare token, IRC's `oauth:` form, the whole redirect URL or the website's
 * `chatter://login` link.
 */
object PastedToken {
    fun read(text: String): String? {
        val trimmed = text.trim()
        val token = LinkedToken.read(trimmed)
            ?: LoginUrls.answer(trimmed)["access_token"]
            ?: trimmed.removePrefix("oauth:")
        return token.takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }
    }
}
