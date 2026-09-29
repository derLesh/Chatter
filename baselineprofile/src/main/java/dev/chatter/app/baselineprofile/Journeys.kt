package dev.chatter.app.baselineprofile

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/**
 * Chatter as the profiling builds install it: an app of its own, so the Chatter somebody is
 * logged in to on the same phone is neither wiped nor uninstalled. See `profilingBuildTypes` in
 * app/build.gradle.kts.
 */
const val PACKAGE = "dev.chatter.app.profiling"

/**
 * Somewhere that always has something to read while it is live. Another one for a run:
 * `-Pandroid.testInstrumentationRunnerArguments.chatterChannel=<login>`.
 */
private const val DEFAULT_CHANNEL = "xqc"

/** `CHAT_LIST_TAG` in the app, which this module cannot see. */
private const val CHAT_LIST = "chat"

/** `EXTRA_PROFILING_CHANNEL` in the app: the channel to read as a guest. */
private const val EXTRA_CHANNEL = "profiling_channel"

private const val TIMEOUT_MS = 15_000L

/** Long enough for a busy channel to fill the screen with what the chat has to draw. */
private const val MESSAGES_MS = 5_000L

private val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

/** The start of a fresh install: nobody reading, no channel, the login screen. */
fun startLoggedOut() {
    device.executeShellCommand("pm clear $PACKAGE")
}

/**
 * Reads a busy channel as a guest, and waits for its chat. It stays that way until
 * [startLoggedOut], so every start after this one goes straight to the chat.
 */
fun startInChat() {
    val channel = InstrumentationRegistry.getArguments().getString("chatterChannel") ?: DEFAULT_CHANNEL
    // Straight to the activity rather than through the launcher entry: the extra is not for it.
    device.executeShellCommand("am start -W -n $PACKAGE/dev.chatter.app.MainActivity --es $EXTRA_CHANNEL $channel")
    chat()
}

/** The chat on screen; fails when it does not show up. */
fun chat(): UiObject2 = device.wait(Until.findObject(By.res(CHAT_LIST)), TIMEOUT_MS)
    ?: error("The chat did not show up")

/** Waits for messages to arrive, then scrolls back through them and down to the newest again. */
fun readChat() {
    SystemClock.sleep(MESSAGES_MS)
    val list = chat()
    // Clear of the edges, where a fling would be the system's back or home gesture.
    list.setGestureMargin(device.displayWidth / 5)
    repeat(2) {
        // The newest message is at the bottom, so up is back in time.
        list.fling(Direction.UP)
        list.fling(Direction.DOWN)
    }
    device.waitForIdle()
}
