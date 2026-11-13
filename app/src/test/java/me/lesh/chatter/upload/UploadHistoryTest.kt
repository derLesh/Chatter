package me.lesh.chatter.upload

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The last ten uploads: newest first, kept across starts, previews leaving with their entry. */
@OptIn(ExperimentalCoroutinesApi::class)
class UploadHistoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val picture = Picture(File("shared.png"), "image/png")
    private var clock = 0L

    private fun history(dir: File, scope: TestScope) = UploadHistory(
        dir = dir,
        scope = scope,
        makePreview = { _, target -> target.writeText("preview") },
        io = StandardTestDispatcher(scope.testScheduler),
        now = { clock++ },
    )

    private fun uploaded(n: Int) = UploadedPicture("https://kappa.lol/$n", null)

    @Test
    fun theNewestComesFirstAndOnlyTenStay() = runTest {
        val dir = folder.newFolder()
        val history = history(dir, this)
        repeat(12) { history.add(picture, uploaded(it), Uploader.KappaLol) }

        val links = history.uploads.value.map { it.link }
        assertEquals((11 downTo 2).map { "https://kappa.lol/$it" }, links)
        // Only the ten kept have a preview left.
        assertEquals(10, dir.listFiles { f -> f.name.endsWith(".jpg") }!!.size)
    }

    @Test
    fun theListIsReadAgainAfterARestart() = runTest {
        val dir = folder.newFolder()
        history(dir, this).add(picture, uploaded(1), Uploader.Catbox)

        val again = history(dir, this)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("https://kappa.lol/1"), again.uploads.value.map { it.link })
        assertEquals(Uploader.Catbox, again.uploads.value.single().uploader)
    }

    /** An upload right after the start must not push out the ones from before. */
    @Test
    fun anUploadBeforeTheListIsReadKeepsTheOlderOnes() = runTest {
        val dir = folder.newFolder()
        history(dir, this).add(picture, uploaded(1), Uploader.KappaLol)

        val again = history(dir, this)
        again.add(picture, uploaded(2), Uploader.KappaLol)
        assertEquals(listOf("https://kappa.lol/2", "https://kappa.lol/1"), again.uploads.value.map { it.link })
    }

    @Test
    fun removingTakesThePreviewAlong() = runTest {
        val dir = folder.newFolder()
        val history = history(dir, this)
        val upload = history.add(picture, uploaded(1), Uploader.KappaLol)
        assertTrue(history.preview(upload).exists())

        history.remove(upload.id)
        assertTrue(history.uploads.value.isEmpty())
        assertFalse(history.preview(upload).exists())
    }

    @Test
    fun aPreviewThatCannotBeMadeLeavesTheLink() = runTest {
        val dir = folder.newFolder()
        val history = UploadHistory(dir, this, makePreview = { _, _ -> error("not a picture") }, io = StandardTestDispatcher(testScheduler))
        history.add(picture, uploaded(1), Uploader.KappaLol)
        assertEquals(1, history.uploads.value.size)
    }
}
