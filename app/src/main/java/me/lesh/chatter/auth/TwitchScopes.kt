package me.lesh.chatter.auth

/**
 * The scopes Chatter asks for, all at login. That includes what only planned features need: a token
 * cannot gain scopes, so each one added later sends every user to log in again. Left out is what
 * reaches past the chat, like the stream key, the email address or the videos.
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
        // The names of rewards redeemed with a message in the user's own channel.
        "channel:read:redemptions",

        // Not used yet. Chat and whispers over EventSub, the channels the user moderates, their
        // subs, and /clip.
        "user:read:chat", "user:write:chat", "user:read:whispers",
        "user:read:moderated_channels", "user:read:subscriptions", "clips:edit",
        // Not used yet. More moderation: /warn, AutoMod, blocked terms, shield mode, unban requests,
        // suspicious users, /mods, /vips and how long someone follows.
        "moderator:manage:warnings", "moderator:manage:automod", "moderator:manage:automod_settings",
        "moderator:manage:blocked_terms", "moderator:manage:shield_mode", "moderator:manage:unban_requests",
        "moderator:manage:suspicious_users", "moderator:read:moderators", "moderator:read:vips",
        "moderator:read:followers",
        // Not used yet. The user's own channel: who moderated what, title and category, ads, polls,
        // predictions, rewards, subs, bits, hype trains, goals and charity.
        "channel:moderate", "channel:manage:broadcast", "channel:edit:commercial", "channel:manage:ads",
        "channel:manage:polls", "channel:manage:predictions", "channel:manage:redemptions",
        "channel:read:subscriptions", "bits:read", "channel:read:hype_train", "channel:read:goals",
        "channel:read:charity",
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
