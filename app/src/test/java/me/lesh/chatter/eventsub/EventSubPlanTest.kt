package me.lesh.chatter.eventsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventSubPlanTest {
    @Test
    fun aViewerWhoModeratesNothingNeedsNoSocket() {
        assertTrue(EventSubPlan.subscriptions("me", scopes = null, moderated = emptyList()).isEmpty())
    }

    @Test
    fun aModHearsOfAutoModInEachChannelInOrder() {
        val subs = EventSubPlan.subscriptions("me", scopes = null, moderated = listOf("10", "20"))
        assertEquals(
            listOf("10", "10", "20", "20"),
            subs.filter { it.type.startsWith("automod.") }.map { it.condition["broadcaster_user_id"] },
        )
        assertTrue(subs.all { it.condition["moderator_user_id"] == "me" })
    }

    @Test
    fun aLoginWithoutTheScopeAsksForNoAutoMod() {
        val subs = EventSubPlan.subscriptions("me", scopes = setOf("chat:read"), moderated = listOf("10"))
        assertTrue(subs.none { it.type.startsWith("automod.") })
    }

    @Test
    fun theModLogNeedsEveryScopeItReads() {
        val all = EventSubPlan.subscriptions("me", scopes = null, moderated = listOf("10"))
        assertTrue(all.any { it.type == "channel.moderate" })
        val partly = EventSubPlan.subscriptions(
            "me", scopes = setOf("moderator:manage:banned_users", "moderator:manage:automod"), moderated = listOf("10"),
        )
        assertTrue(partly.none { it.type == "channel.moderate" })
    }
}
