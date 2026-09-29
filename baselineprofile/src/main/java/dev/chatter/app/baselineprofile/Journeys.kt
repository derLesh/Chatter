package dev.chatter.app.baselineprofile

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue

/**
 * Chatter as the profiling builds install it: an app of its own, so the Chatter somebody is
 * logged in to on the same phone is neither wiped nor uninstalled. See `profilingBuildTypes` in
 * app/build.gradle.kts.
 */
const val PACKAGE = "dev.chatter.app.profiling"

/** Somewhere that always has something to read while it is live; `profiling.channel` changes it. */
private const val DEFAULT_CHANNEL = "xqc"

/** `CHAT_LIST_TAG` in the app, which this module cannot see. */
private const val CHAT_LIST = "chat"

/** The intent extras only the profiling builds listen for; `EXTRA_PROFILING_*` in the app. */
private const val EXTRA_TOKEN = "profiling_token"
private const val EXTRA_CHANNEL = "profiling_channel"

private const val TIMEOUT_MS = 15_000L

/** Long enough for a busy channel to fill the screen with what the chat has to draw. */
private const val MESSAGES_MS = 5_000L

private val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

/** The start of a fresh install: nobody logged in, no channel, the login screen. */
fun startLoggedOut() {
    device.executeShellCommand("pm clear $PACKAGE")
}

/**
 * Logs in with `profiling.token` from local.properties and joins a busy channel, then waits for
 * its chat. What it does stays with the app until [startLoggedOut], so a journey starts from here
 * and needs no token of its own. Skips the test when there is no token: nothing gets past the login
 * without one.
 */
fun startInChat() {
    val arguments = InstrumentationRegistry.getArguments()
    val token = arguments.getString("chatterToken")
    assumeTrue("No profiling.token in local.properties, so the chat cannot be reached", !token.isNullOrBlank())
    val channel = arguments.getString("chatterChannel") ?: DEFAULT_CHANNEL
    // The first look at the chat asks for it otherwise, and the dialog would sit on the chat.
    device.executeShellCommand("pm grant $PACKAGE android.permission.POST_NOTIFICATIONS")
    // Straight to the activity rather than through the launcher entry: the extras are not for it.
    device.executeShellCommand(
        "am start -W -n $PACKAGE/dev.chatter.app.MainActivity --es $EXTRA_TOKEN $token --es $EXTRA_CHANNEL $channel",
    )
    chat()
}

/** The chat on screen; fails when it does not show up, which a token Twitch rejects looks like. */
fun chat(): UiObject2 = device.wait(Until.findObject(By.res(CHAT_LIST)), TIMEOUT_MS)
    ?: error("The chat did not show up. Is profiling.token still valid?")

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
