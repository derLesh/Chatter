package dev.chatter.app.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Writes the baseline profile for Chatter: the three ways into the app that matter most.
 *
 * The login screen is what a fresh install starts on, once. The chat is what every start after
 * that ends on, and reading it — messages arriving, being built and drawn, the list scrolled — is
 * what the app does most. The journeys read it as a guest, so they need no Twitch login; what only
 * a login would run (Twitch's badges, writing) is not in here, and the hand-written
 * app/src/main/baseline-prof.txt names those packages whole.
 */
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun loginScreen() {
        startLoggedOut()
        rule.collect(PACKAGE, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
            // The first frame is not the last word: the login screen settles once the stored
            // token has been looked for, and waiting for it keeps that work in the profile too.
            device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), TIMEOUT_MS)
            device.waitForIdle()
        }
    }

    @Test
    fun chatStart() {
        startInChat()
        rule.collect(PACKAGE, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
            chat()
        }
    }

    /** Not part of the start, so kept out of the startup profile, which decides the dex layout. */
    @Test
    fun chatReading() {
        startInChat()
        rule.collect(PACKAGE) {
            pressHome()
            startActivityAndWait()
            readChat()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
