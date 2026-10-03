package dev.chatter.app.auth

/**
 * The scopes Chatter asks for, all at login so moderators can moderate right away. Each one is used
 * by a feature; a token that can do more than the app needs is only more valuable to whoever steals
 * it.
 */
object TwitchScopes {
    val ALL = listOf(
        // The chat itself, over IRC.
        "chat:read", "chat:edit",
        // The user's own emotes in the picker, and the follow count on the account page.
        "user:read:emotes", "user:read:follows",
        // /color.
        "user:manage:chat_color",
        // Whispers arrive over IRC only for tokens with whispers:read. Sending goes through Helix,
        // which wants user:manage:whispers.
        "whispers:read", "user:manage:whispers",
        // The block list, and blocking from the user card.
        "user:read:blocked_users", "user:manage:blocked_users",
        // The chatter list for name suggestions; Twitch only answers where the user moderates.
        "moderator:read:chatters",
        // Moderator commands and the user card's timeout, ban and delete; only where the user is
        // moderator or broadcaster.
        "moderator:manage:banned_users", "moderator:manage:chat_messages", "moderator:manage:chat_settings",
        "moderator:manage:announcements", "moderator:manage:shoutouts",
        // /mod, /vip and /raid in the user's own channel.
        "channel:manage:moderators", "channel:manage:vips", "channel:manage:raids",
    )

    /**
     * Whether a token with [granted] may use [scope]. [granted] is null for accounts stored before
     * scopes were kept; those may try and Twitch decides.
     */
    fun allows(granted: Set<String>?, scope: String): Boolean = granted == null || scope in granted

    /**
     * Scopes Chatter uses that [granted] lacks, e.g. from a login by an older version. Logging in
     * again asks for them.
     */
    fun missing(granted: Set<String>?): Set<String> = if (granted == null) emptySet() else ALL.toSet() - granted
}
