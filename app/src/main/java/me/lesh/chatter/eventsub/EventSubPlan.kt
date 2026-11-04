package me.lesh.chatter.eventsub

import me.lesh.chatter.auth.TwitchScopes

/**
 * Which subscriptions an account needs, from what it may see. Only what IRC does not carry is
 * subscribed, so a guest or a viewer in a quiet channel opens no socket at all.
 */
object EventSubPlan {
    /**
     * [moderated] are the ids of the joined channels where [userId] is mod or broadcaster, most
     * important first; [visited] those of the joined channels the user had on screen lately, newest
     * first. Null [scopes] are an account stored before scopes were kept; Twitch decides.
     */
    fun subscriptions(
        userId: String,
        scopes: Set<String>?,
        moderated: List<String>,
        visited: List<String> = emptyList(),
    ): List<EventSubSubscription> {
        fun allows(scope: String) = TwitchScopes.allows(scopes, scope)
        val autoMod = allows(AUTOMOD_SCOPE)
        val modLog = MOD_LOG_SCOPES.all(::allows)
        return buildList {
            for (channelId in moderated) {
                val asMod = mapOf("broadcaster_user_id" to channelId, "moderator_user_id" to userId)
                if (autoMod) {
                    add(EventSubSubscription("automod.message.hold", "2", asMod))
                    add(EventSubSubscription("automod.message.update", "2", asMod))
                }
                if (modLog) add(EventSubSubscription("channel.moderate", "2", asMod))
            }
            // AutoMod never holds a mod's message. Only channels the user looked at: they are the
            // ones written in, and every new session subscribes again.
            if (allows(CHAT_SCOPE)) {
                val moderatedSet = moderated.toHashSet()
                for (channelId in visited.asSequence().filter { it !in moderatedSet }.take(MAX_VISITED)) {
                    val asUser = mapOf("broadcaster_user_id" to channelId, "user_id" to userId)
                    add(EventSubSubscription("channel.chat.user_message_hold", "1", asUser))
                    add(EventSubSubscription("channel.chat.user_message_update", "1", asUser))
                }
            }
            // Needs no scope, so Twitch counts it against a small budget per account, which a second
            // phone with Chatter shares.
            for (channelId in visited.take(MAX_TITLE_CHANNELS)) {
                add(EventSubSubscription("channel.update", "2", mapOf("broadcaster_user_id" to channelId)))
            }
        }
    }

    const val AUTOMOD_SCOPE = "moderator:manage:automod"
    private const val CHAT_SCOPE = "user:read:chat"

    /** How many of the channels looked at hear of the user's held messages. */
    const val MAX_VISITED = 20

    /** Twitch allows ten subscriptions that need no scope; this leaves room for another phone. */
    const val MAX_TITLE_CHANNELS = 5

    /** `channel.moderate` reports every kind of action, so Twitch wants a scope to read each. */
    private val MOD_LOG_SCOPES = listOf(
        "moderator:manage:banned_users", "moderator:manage:chat_messages", "moderator:manage:chat_settings",
        "moderator:manage:blocked_terms", "moderator:manage:unban_requests", "moderator:manage:warnings",
        "moderator:read:moderators", "moderator:read:vips",
    )
}
