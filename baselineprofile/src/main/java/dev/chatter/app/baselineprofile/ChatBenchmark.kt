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
 * What the baseline profile is worth: the cold start into the chat and the frames of reading it,
 * each measured with the profile and without it — the way the app runs right after an install or
 * an update, before Android has compiled anything of its own accord.
 *
 * `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest` on the phone. It reads a busy
 * channel as a guest, like the generator (see Journeys.kt). The messages are live, so no two runs
 * read the same chat; compare the two modes of one run with each other, not with another run.
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

        /** Fails rather than measuring nothing when the build carries no profile. */
        val WITH_PROFILE = CompilationMode.Partial(BaselineProfileMode.Require)
    }
}
