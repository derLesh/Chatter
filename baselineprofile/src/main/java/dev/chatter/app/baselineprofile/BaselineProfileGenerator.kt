package dev.chatter.app.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Writes the baseline profile for Chatter.
 *
 * A fresh install is logged out, so what this can reach is the start itself: the activity, the
 * theme, Compose starting up and drawing its first frame, and the login screen. That is the path
 * every cold start takes before anything else, and the one that is slowest without a profile.
 *
 * The chat itself cannot be reached from here — it is behind a Twitch login, and this runs on a
 * clean install with no token. Its drawing code still benefits from the AndroidX profiles that
 * ship inside the Compose libraries.
 */
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(
        packageName = PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        // The first frame is not the last word: the login screen settles once the stored token
        // has been looked for, and waiting for it keeps that work in the profile too.
        device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), TIMEOUT_MS)
        device.waitForIdle()
    }

    private companion object {
        const val PACKAGE = "dev.chatter.app"
        const val TIMEOUT_MS = 5_000L
    }
}
