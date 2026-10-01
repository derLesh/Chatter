package dev.chatter.app.auth

/**
 * What Chatter asks Twitch to be allowed to do for an account.
 *
 * All of it at the login, so that somebody who moderates a channel can moderate it the moment
 * they open it, without being sent through Twitch's page a second time. But only what Chatter
 * actually does: every scope here has a feature behind it, and one that loses its feature goes.
 * A token that can do more than the app ever asks of it is worth more to whoever gets hold of
 * it, and buys the user nothing.
 */
object TwitchScopes {
    val ALL = listOf(
        // The chat itself, over IRC.
        "chat:read", "chat:edit",
        // The user's own emotes in the picker, and the follow count on the account page.
        "user:read:emotes", "user:read:follows",
        // /color.
        "user:manage:chat_color",
        // Whispers arrive over the chat connection, but only for a token that asked for them.
        // Sending them goes through Helix, which wants the newer scope of the two.
        "whispers:read", "user:manage:whispers",
        // The block list, and blocking from the user card.
        "user:read:blocked_users", "user:manage:blocked_users",
        // The chatter list for name suggestions; Twitch only answers where the user moderates.
        "moderator:read:chatters",
        // The moderator commands and the user card's timeout, ban and delete; Twitch only lets
        // them through where the user is moderator or broadcaster.
        "moderator:manage:banned_users", "moderator:manage:chat_messages", "moderator:manage:chat_settings",
        "moderator:manage:announcements", "moderator:manage:shoutouts",
        // /mod, /vip and /raid in the user's own channel.
        "channel:manage:moderators", "channel:manage:vips", "channel:manage:raids",
    )

    /**
     * Whether a token with [granted] may do what [scope] allows. An account stored before the
     * app kept its scopes has [granted] null; it is let try, and Twitch's answer decides.
     */
    fun allows(granted: Set<String>?, scope: String): Boolean = granted == null || scope in granted

    /**
     * What Chatter uses that a token with [granted] was not given — a login from a version that
     * asked for less. Logging in again asks for it.
     */
    fun missing(granted: Set<String>?): Set<String> = if (granted == null) emptySet() else ALL.toSet() - granted
}
