package dev.chatter.app.flows

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.chatter.app.ChatterApp
import dev.chatter.app.MainActivity
import dev.chatter.app.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The settings and the inbox stay open, on the page the user was on, when Android recreates the
 * activity, as it does when the phone turns or the process comes back.
 *
 * Read as a guest without channels: no login, and no message arrives to change the screen.
 */
@RunWith(AndroidJUnit4::class)
class ScreenRestoreTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val app = ApplicationProvider.getApplicationContext<ChatterApp>()

    @Before
    fun readAsGuest() = runBlocking {
        app.container.settings.setOnboardingSeen(true)
        app.container.auth.continueAsGuest()
    }

    @Test
    fun theSettingsPageComesBack() {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            tap(By.desc(app.getString(R.string.settings)))
            tap(By.text(app.getString(R.string.settings_filters)))
            expect(By.text(app.getString(R.string.settings_rules)))

            activity.recreate()
            expect(By.text(app.getString(R.string.settings_rules)))
        }
    }

    @Test
    fun theInboxAndItsTabComeBack() {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            tap(By.desc(app.getString(R.string.inbox_title)))
            tap(By.text(app.getString(R.string.inbox_tab_whispers)))
            expect(By.text(app.getString(R.string.inbox_whispers_empty)))

            activity.recreate()
            expect(By.text(app.getString(R.string.inbox_whispers_empty)))
        }
    }

    private fun tap(selector: BySelector) {
        expect(selector)
        device.findObject(selector).click()
    }

    private fun expect(selector: BySelector) {
        assertTrue("Not on screen: $selector", device.wait(Until.hasObject(selector), TIMEOUT_MS))
    }

    private companion object {
        /** Long enough for a slow emulator to start the app; a missing screen fails only then. */
        const val TIMEOUT_MS = 10_000L
    }
}
