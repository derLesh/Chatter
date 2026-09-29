package dev.chatter.app.service

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which ends of the process the notification settings warn about. */
class BackgroundStopsTest {
    @Test
    fun theLatestStopWhileListeningIsTheOneShown() {
        val exits = listOf(
            ProcessExit(at = 100, reason = ApplicationExitInfo.REASON_LOW_MEMORY, listening = true),
            ProcessExit(at = 300, reason = ApplicationExitInfo.REASON_SIGNALED, listening = true),
            ProcessExit(at = 200, reason = ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, listening = true),
        )
        assertEquals(BackgroundStop(300, StopReason.System), BackgroundStops.latest(exits, after = 0))
    }

    @Test
    fun aProcessThatWasNotListeningCostNoMentions() {
        val exits = listOf(ProcessExit(at = 100, reason = ApplicationExitInfo.REASON_LOW_MEMORY, listening = false))
        assertNull(BackgroundStops.latest(exits, after = 0))
    }

    @Test
    fun whatWasAlreadyLookedAtIsNotShownAgain() {
        val exits = listOf(ProcessExit(at = 100, reason = ApplicationExitInfo.REASON_LOW_MEMORY, listening = true))
        assertNull(BackgroundStops.latest(exits, after = 100))
    }

    @Test
    fun endsTheBatterySettingsCannotHelpWithAreLeftOut() {
        val exits = listOf(
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE,
        ).mapIndexed { i, reason -> ProcessExit(at = i + 1L, reason = reason, listening = true) }
        assertNull(BackgroundStops.latest(exits, after = 0))
    }

    @Test
    fun aForceStopIsTheUsersOwnDoing() {
        assertEquals(StopReason.UserRequest, BackgroundStops.reasonOf(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertEquals(StopReason.UserRequest, BackgroundStops.reasonOf(ApplicationExitInfo.REASON_USER_STOPPED))
    }
}
