package dev.chatter.app.service

import dev.chatter.app.channels.LiveAlertChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAlertsTest {
    @Test
    fun channelsInTheListNotifyUntilTurnedOff() {
        val choices = LiveAlertChoices()
        assertTrue(choices.wanted("forsen", inList = true))
        val off = choices.with("forsen", inList = true, enabled = false)
        assertFalse(off.wanted("forsen", inList = true))
        assertTrue(off.with("forsen", inList = true, enabled = true).wanted("forsen", inList = true))
    }

    @Test
    fun otherFollowsOnlyNotifyWhenTurnedOn() {
        val choices = LiveAlertChoices()
        assertFalse(choices.wanted("xqc", inList = false))
        val on = choices.with("xqc", inList = false, enabled = true)
        assertTrue(on.wanted("xqc", inList = false))
        assertEquals(LiveAlertChoices(), on.with("xqc", inList = false, enabled = false))
    }

    @Test
    fun onlyTheStepFromOfflineToLiveCounts() {
        assertEquals(setOf("b"), LiveAlerts.wentLive(before = setOf("a"), now = setOf("a", "b")))
        assertEquals(emptySet<String>(), LiveAlerts.wentLive(before = setOf("a", "b"), now = setOf("a")))
    }

    @Test
    fun followedStreamsAreOnlyAskedForAChannelOutsideTheListThatWasTurnedOn() {
        val list = listOf("forsen")
        assertFalse("nothing turned on by default", LiveAlerts.followedWanted(LiveAlertChoices(), list))
        val on = LiveAlertChoices().with("xqc", inList = false, enabled = true)
        assertTrue(LiveAlerts.followedWanted(on, list))
        assertFalse("added to the list since, it is asked for there", LiveAlerts.followedWanted(on, list + "xqc"))
    }

    @Test
    fun theListIsOnlyAskedForInTheBackgroundWhileOneOfItsChannelsNotifies() {
        val list = listOf("forsen", "xqc")
        assertTrue(LiveAlerts.anyWanted(LiveAlertChoices(), list))
        val oneOff = LiveAlertChoices().with("forsen", inList = true, enabled = false)
        assertTrue(LiveAlerts.anyWanted(oneOff, list))
        assertFalse(LiveAlerts.anyWanted(oneOff.with("xqc", inList = true, enabled = false), list))
    }

    @Test
    fun whatIsLiveAtTheFirstLookDidNotJustGoLive() {
        assertEquals(emptySet<String>(), LiveAlerts.wentLive(before = null, now = setOf("a", "b")))
    }
}
