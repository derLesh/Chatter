package dev.chatter.app.baselineprofile

import android.os.SystemClock
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
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
 * What large emotes cost next to normal ones: frames and memory while reading, and power while the
 * chat is left open, both at the full emote frame rate and at the slower one it drops to when left
 * alone. Large emotes are drawn from images with four times the pixels.
 *
 * Run `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.class=dev.chatter.app.baselineprofile.EmoteSizeBenchmark`.
 * The power tests need a phone that reports its rails; docs/power.md says how to read them.
 */
@OptIn(ExperimentalMetricApi::class)
class EmoteSizeBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var brightnessBefore = ""
    private var brightnessModeBefore = ""

    /** As in [PowerBenchmark]: the display follows the brightness, the room's light must not decide. */
    @Before
    fun fixBrightness() {
        brightnessModeBefore = shell("settings get system screen_brightness_mode")
        brightnessBefore = shell("settings get system screen_brightness")
        shell("settings put system screen_brightness_mode 0")
        shell("settings put system screen_brightness $BRIGHTNESS")
    }

    @After
    fun restore() {
        shell("settings put system screen_brightness $brightnessBefore")
        shell("settings put system screen_brightness_mode $brightnessModeBefore")
        // So that ChatBenchmark, which sets nothing, does not run with large emotes.
        startLoggedOut()
    }

    @Test
    fun readNormal() = read(large = false)

    @Test
    fun readLarge() = read(large = true)

    @Test
    fun watchNormal() = watch(large = false, untouchedMs = SETTLE_MS)

    @Test
    fun watchLarge() = watch(large = true, untouchedMs = SETTLE_MS)

    @Test
    fun idleNormal() = watch(large = false, untouchedMs = IDLE_MS)

    @Test
    fun idleLarge() = watch(large = true, untouchedMs = IDLE_MS)

    private fun read(large: Boolean) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
        iterations = READ_ITERATIONS,
        setupBlock = {
            killProcess()
            startInChat(settings(large))
        },
    ) {
        readChat()
    }

    /** Measures a minute of the chat left alone, after [untouchedMs] of leaving it alone already. */
    private fun watch(large: Boolean, untouchedMs: Long) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(PowerMetric(PowerMetric.Power(EVERY_RAIL)), MemoryUsageMetric(MemoryUsageMetric.Mode.Last)),
        iterations = WATCH_ITERATIONS,
        setupBlock = {
            killProcess()
            startInChat(settings(large))
            SystemClock.sleep(untouchedMs)
        },
    ) {
        SystemClock.sleep(WATCH_MS)
    }

    private fun shell(command: String): String = device.executeShellCommand(command).trim()

    private fun settings(large: Boolean) = mapOf(
        LARGE_EMOTES to large,
        ANIMATED_EMOTES to true,
        KEEP_SCREEN_ON to true,
    )

    private companion object {
        const val READ_ITERATIONS = 5
        const val WATCH_ITERATIONS = 3
        const val WATCH_MS = 60_000L

        /** Time for the settings to be saved and the screen to fill with messages. */
        const val SETTLE_MS = 10_000L

        /** Past `EmoteFrameRate.IDLE_AFTER_MS` (3 minutes), after which emotes run slower. */
        const val IDLE_MS = 200_000L

        const val BRIGHTNESS = 128

        /** `EXTRA_PROFILING_` in the app, which this module cannot reference. */
        const val LARGE_EMOTES = "profiling_large_emotes"
        const val ANIMATED_EMOTES = "profiling_animated_emotes"
        const val KEEP_SCREEN_ON = "profiling_keep_screen_on"

        val EVERY_RAIL = PowerCategory.entries.associateWith { PowerCategoryDisplayLevel.BREAKDOWN }
    }
}
