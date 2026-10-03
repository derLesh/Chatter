package dev.chatter.app.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Measures the baseline profile's effect: cold start into the chat and frame timing while reading,
 * each with and without the profile (as right after an install or update).
 *
 * Run `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`. It reads a busy channel as
 * a guest (see Journeys.kt); the messages are live, so compare the two modes within one run, not
 * across runs.
 */
class ChatBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Before
    fun openChat() = startInChat()

    @Test
    fun startWithoutProfile() = start(CompilationMode.None())

    @Test
    fun startWithProfile() = start(WITH_PROFILE)

    @Test
    fun readWithoutProfile() = read(CompilationMode.None())

    @Test
    fun readWithProfile() = read(WITH_PROFILE)

    private fun start(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
    ) {
        startActivityAndWait()
        chat()
    }

    private fun read(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = mode,
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            startActivityAndWait()
        },
    ) {
        readChat()
    }

    private companion object {
        const val ITERATIONS = 10

        /** Fails instead of measuring nothing when the build has no profile. */
        val WITH_PROFILE = CompilationMode.Partial(BaselineProfileMode.Require)
    }
}
