package dev.chatter.app.chat

import dev.chatter.app.settings.NameStyle

/**
 * A chatter's name as [style] shows it, or the [nickname] the user gave them. Twitch display names
 * differ from the login only in case, unless written in another script: "다니엘" for "daniel".
 */
fun chatterName(displayName: String?, login: String?, style: NameStyle, nickname: String? = null): String {
    nickname?.let { return it }
    val display = displayName?.ifEmpty { null } ?: login ?: ""
    if (login.isNullOrEmpty()) return display
    return when (style) {
        NameStyle.DisplayName -> display
        NameStyle.Login -> login
        NameStyle.OtherScripts -> if (display.equals(login, ignoreCase = true)) display else "$display ($login)"
        NameStyle.Both -> if (display == login) display else "$display ($login)"
    }
}
