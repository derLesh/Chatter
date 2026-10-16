package dev.chatter.app.baselineprofile

import android.os.SystemClock
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.PowerCategory
import androidx.benchmark.macro.PowerCategoryDisplayLevel
import androidx.benchmark.macro.PowerMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * What an open chat draws from each power rail of the phone, read as a second screen: nobody
 * touches it, messages keep coming. The results name the values `…Uw`, but they are mW. Per rail
 * and not per category: on a Pixel 11 the library files Wi-Fi under "uncategorized".
 *
 * Run `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.class=dev.chatter.app.baselineprofile.PowerBenchmark`.
 * docs/power.md says how to read it and holds the numbers so far.
 */
@OptIn(ExperimentalMetricApi::class)
class PowerBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var brightnessBefore = ""
    private var brightnessModeBefore = ""

    @Before
    fun fixBrightness() {
        check(PowerMetric.deviceSupportsHighPrecisionTracking()) {
            "This phone does not report its power rails; a Pixel 6 or newer does"
        }
        brightnessModeBefore = shell("settings get system screen_brightness_mode")
        brightnessBefore = shell("settings get system screen_brightness")
        // The display draws the most and follows the brightness; the room's light must not decide.
        shell("settings put system screen_brightness_mode 0")
        shell("settings put system screen_brightness $BRIGHTNESS")
    }

    @After
    fun restore() {
        shell("settings put system screen_brightness $brightnessBefore")
        shell("settings put system screen_brightness_mode $brightnessModeBefore")
        // So that ChatBenchmark, which sets nothing, does not run with what was set here.
        startLoggedOut()
    }

    @Test
    fun defaults() = watch(DEFAULTS)

    @Test
    fun stillEmotes() = watch(DEFAULTS + (ANIMATED_EMOTES to false))

    @Test
    fun pureBlack() = watch(DEFAULTS + (PURE_BLACK to true))

    @Test
    fun noSmoothScrolling() = watch(DEFAULTS + (SMOOTH_SCROLLING to false))

    private fun watch(settings: Map<String, Boolean>) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(PowerMetric(PowerMetric.Power(EVERY_RAIL))),
        iterations = ITERATIONS,
        setupBlock = {
            // Every run starts alike, not with the images and memory the run before left.
            killProcess()
            startInChat(settings)
            SystemClock.sleep(SETTLE_MS)
        },
    ) {
        SystemClock.sleep(WATCH_MS)
    }

    private fun shell(command: String): String = device.executeShellCommand(command).trim()

    private companion object {
        const val ITERATIONS = 3
        const val WATCH_MS = 60_000L

        /** Time for the settings to be saved and the screen to fill with messages. */
        const val SETTLE_MS = 10_000L

        /** Half of the 0–255 scale, a room at night more than a sunny window. */
        const val BRIGHTNESS = 128

        /** `EXTRA_PROFILING_` in the app, which this module cannot reference. */
        const val DARK_THEME = "profiling_dark_theme"
        const val PURE_BLACK = "profiling_pure_black"
        const val ANIMATED_EMOTES = "profiling_animated_emotes"
        const val SMOOTH_SCROLLING = "profiling_smooth_scrolling"
        const val KEEP_SCREEN_ON = "profiling_keep_screen_on"

        /**
         * The app's defaults, but always dark, so that pure black has something to change, and with
         * the screen kept on, as for a chat next to the stream.
         */
        val DEFAULTS = mapOf(
            DARK_THEME to true,
            PURE_BLACK to false,
            ANIMATED_EMOTES to true,
            SMOOTH_SCROLLING to true,
            KEEP_SCREEN_ON to true,
        )

        val EVERY_RAIL = PowerCategory.entries.associateWith { PowerCategoryDisplayLevel.BREAKDOWN }
    }
}
