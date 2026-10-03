package dev.chatter.app.baselineprofile

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/**
 * The profiling builds' package: a separate app, so the user's own Chatter on the phone is neither
 * wiped nor uninstalled. See `profilingBuildTypes` in app/build.gradle.kts.
 */
const val PACKAGE = "dev.chatter.app.profiling"

/**
 * Usually has chat while live. Override per run with
 * `-Pandroid.testInstrumentationRunnerArguments.chatterChannel=<login>`.
 */
private const val DEFAULT_CHANNEL = "xqc"

/** `CHAT_LIST_TAG` in the app, which this module cannot reference. */
private const val CHAT_LIST = "chat"

/** `EXTRA_PROFILING_CHANNEL` in the app: the channel to read as a guest. */
private const val EXTRA_CHANNEL = "profiling_channel"

private const val TIMEOUT_MS = 15_000L

/** Long enough for a busy channel to fill the screen. */
private const val MESSAGES_MS = 5_000L

private val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

/** A fresh install's start: no channel, login screen. */
fun startLoggedOut() {
    device.executeShellCommand("pm clear $PACKAGE")
}

/**
 * Reads a busy channel as a guest and waits for its chat. Lasts until [startLoggedOut], so every
 * following start goes straight to the chat.
 */
fun startInChat() {
    val channel = InstrumentationRegistry.getArguments().getString("chatterChannel") ?: DEFAULT_CHANNEL
    // Directly to the activity, not the launcher alias: the extra is not for the launcher.
    device.executeShellCommand("am start -W -n $PACKAGE/dev.chatter.app.MainActivity --es $EXTRA_CHANNEL $channel")
    chat()
}

/** The chat list; fails if it does not appear. */
fun chat(): UiObject2 = device.wait(Until.findObject(By.res(CHAT_LIST)), TIMEOUT_MS)
    ?: error("The chat did not show up")

/** Waits for messages, then scrolls back through them and down to the newest again. */
fun readChat() {
    SystemClock.sleep(MESSAGES_MS)
    val list = chat()
    // Away from the edges, where a fling would be a system back or home gesture.
    list.setGestureMargin(device.displayWidth / 5)
    repeat(2) {
        // The newest message is at the bottom, so up goes back in time.
        list.fling(Direction.UP)
        list.fling(Direction.DOWN)
    }
    device.waitForIdle()
}
