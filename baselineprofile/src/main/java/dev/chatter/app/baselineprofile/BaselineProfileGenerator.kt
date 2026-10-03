package dev.chatter.app.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Generates Chatter's baseline profile from the three most important journeys: the login screen of
 * a fresh install, the start into the chat, and reading a busy chat. The journeys read as a guest,
 * so what needs a login (Twitch badges, writing) is covered by the hand-written
 * app/src/main/baseline-prof.txt instead.
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
            // The login screen settles once the stored token was checked; waiting keeps that work
            // in the profile.
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
