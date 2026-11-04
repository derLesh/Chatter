package me.lesh.chatter.eventsub

import me.lesh.chatter.auth.TwitchScopes

/**
 * Which subscriptions an account needs, from what it may see. Only what IRC does not carry is
 * subscribed, so a guest or a viewer in a quiet channel opens no socket at all.
 */
object EventSubPlan {
    /**
     * [moderated] are the ids of the joined channels where [userId] is mod or broadcaster, most
     * important first. Null [scopes] are an account stored before scopes were kept; Twitch decides.
     */
    fun subscriptions(
        userId: String,
        scopes: Set<String>?,
        moderated: List<String>,
    ): List<EventSubSubscription> {
        fun allows(scope: String) = TwitchScopes.allows(scopes, scope)
        val autoMod = allows(AUTOMOD_SCOPE)
        return buildList {
            for (channelId in moderated) {
                val asMod = mapOf("broadcaster_user_id" to channelId, "moderator_user_id" to userId)
                if (autoMod) {
                    add(EventSubSubscription("automod.message.hold", "2", asMod))
                    add(EventSubSubscription("automod.message.update", "2", asMod))
                }
            }
        }
    }

    const val AUTOMOD_SCOPE = "moderator:manage:automod"
}
