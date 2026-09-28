package dev.chatter.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SendLimitsTest {
    @Test
    fun charactersAreCountedTheWayAPersonCountsThem() {
        assertEquals(5, SendLimits.length("hallo"))
        // Two UTF-16 units each, one character each.
        assertEquals(3, SendLimits.length("😀😀!"))
    }

    @Test
    fun slowModeHoldsBackViewersOnly() {
        val slow = RoomState(slow = 30)
        assertEquals(30, SendLimits.slowSeconds(slow, ChatRole.Viewer))
        assertEquals("before USERSTATE the user is nobody special", 30, SendLimits.slowSeconds(slow, null))
        assertEquals(0, SendLimits.slowSeconds(slow, ChatRole.Vip))
        assertEquals(0, SendLimits.slowSeconds(slow, ChatRole.Moderator))
        assertEquals(0, SendLimits.slowSeconds(RoomState(), ChatRole.Viewer))
        assertEquals(0, SendLimits.slowSeconds(null, ChatRole.Viewer))
    }

    @Test
    fun subscriberOnlyChatIsNamedToThoseWhoAreNot() {
        val subs = RoomState(subsOnly = true)
        assertEquals(ChatRestriction.SubsOnly, SendLimits.restriction(subs, ChatRole.Viewer, subscribed = false))
        assertNull(SendLimits.restriction(subs, ChatRole.Viewer, subscribed = true))
        assertNull(SendLimits.restriction(subs, ChatRole.Vip, subscribed = false))
    }

    @Test
    fun followersOnlyIsNamedEvenForZeroMinutes() {
        assertEquals(ChatRestriction.FollowersOnly, SendLimits.restriction(RoomState(followersOnly = 0), ChatRole.Viewer, false))
        assertEquals(ChatRestriction.FollowersOnly, SendLimits.restriction(RoomState(followersOnly = 10), null, false))
        assertNull(SendLimits.restriction(RoomState(followersOnly = -1), ChatRole.Viewer, false))
        assertNull(SendLimits.restriction(RoomState(followersOnly = 10), ChatRole.Moderator, false))
    }

    @Test
    fun emoteOnlyLetsOnlyModeratorsPast() {
        val emotes = RoomState(emoteOnly = true)
        assertEquals(ChatRestriction.EmoteOnly, SendLimits.restriction(emotes, ChatRole.Vip, true))
        assertNull(SendLimits.restriction(emotes, ChatRole.Moderator, false))
        assertNull(SendLimits.restriction(emotes, ChatRole.Broadcaster, false))
    }

    @Test
    fun theHardestModeIsTheOneNamed() {
        val all = RoomState(subsOnly = true, followersOnly = 0, emoteOnly = true)
        assertEquals(ChatRestriction.SubsOnly, SendLimits.restriction(all, ChatRole.Viewer, false))
        assertEquals(ChatRestriction.EmoteOnly, SendLimits.restriction(all, ChatRole.Viewer, true))
    }
}
