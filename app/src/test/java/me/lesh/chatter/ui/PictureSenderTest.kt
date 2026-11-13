package me.lesh.chatter.ui

import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import me.lesh.chatter.chat.SendResult
import me.lesh.chatter.net.HttpException
import me.lesh.chatter.upload.Deletion
import me.lesh.chatter.upload.Picture
import me.lesh.chatter.upload.UploadedPicture
import me.lesh.chatter.upload.Uploader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/** Sending a picture: one tap uploads and sends, and nothing is uploaded twice. */
@OptIn(ExperimentalCoroutinesApi::class)
class PictureSenderTest {
    private val picture = Picture(File("shared.png"), "image/png")
    private val online = UploadedPicture("https://kappa.lol/AbCdE", Deletion("https://kappa.lol/api/delete?key=k", inBrowser = false))

    /** Hands every upload and message to the test, which decides how each one ends. */
    private class ControlledOutbox : PictureOutbox {
        val uploads = ArrayDeque<CompletableDeferred<UploadedPicture>>()
        val sends = ArrayDeque<Pair<String, CompletableDeferred<SendResult>>>()
        val remembered = mutableListOf<UploadedPicture>()
        val reported = mutableListOf<SendResult>()
        val discarded = mutableListOf<Picture>()

        override suspend fun upload(picture: Picture, uploader: Uploader, onProgress: (Float) -> Unit): UploadedPicture =
            CompletableDeferred<UploadedPicture>().also(uploads::addLast).await()

        override suspend fun remember(picture: Picture, uploaded: UploadedPicture, uploader: Uploader) {
            remembered += uploaded
        }

        override suspend fun send(channel: String, text: String): SendResult =
            CompletableDeferred<SendResult>().also { sends.addLast("$channel: $text" to it) }.await()

        override suspend fun report(result: SendResult) {
            reported += result
        }

        override fun discard(picture: Picture) {
            discarded += picture
        }
    }

    private val scope = TestScope()
    private val outbox = ControlledOutbox()
    private val sentTo = mutableListOf<String>()
    private val sender = PictureSender(scope, outbox) { sentTo += it }

    private fun openAndType(text: String = "look at this") {
        sender.open(picture, "forsen", Uploader.KappaLol)
        sender.text = TextFieldValue(text)
    }

    @Test
    fun sendUploadsThenSendsTheTextWithTheLinkAndCloses() {
        openAndType()
        sender.send()
        scope.runCurrent()
        assertTrue(sender.step is PictureStep.Uploading)

        outbox.uploads.removeFirst().complete(online)
        scope.runCurrent()
        assertEquals(PictureStep.Sending, sender.step)
        assertEquals(listOf(online), outbox.remembered)
        val (message, result) = outbox.sends.removeFirst()
        assertEquals("forsen: look at this https://kappa.lol/AbCdE", message)

        result.complete(SendResult.Ok)
        scope.runCurrent()
        assertFalse(sender.isOpen)
        assertEquals(listOf("forsen"), sentTo)
        assertEquals(listOf(picture), outbox.discarded)
    }

    @Test
    fun aPictureGoesOutWithoutText() {
        openAndType("")
        sender.send()
        scope.runCurrent()
        outbox.uploads.removeFirst().complete(online)
        scope.runCurrent()
        assertEquals("forsen: https://kappa.lol/AbCdE", outbox.sends.removeFirst().first)
    }

    @Test
    fun aRefusedMessageIsSentAgainWithoutUploadingAgain() {
        openAndType()
        sender.send()
        scope.runCurrent()
        outbox.uploads.removeFirst().complete(online)
        scope.runCurrent()
        outbox.sends.removeFirst().second.complete(SendResult.NotConnected)
        scope.runCurrent()
        assertEquals(PictureStep.NotSent, sender.step)
        assertEquals(listOf(SendResult.NotConnected), outbox.reported)
        assertTrue(sender.isOpen)

        sender.send()
        scope.runCurrent()
        assertTrue(outbox.uploads.isEmpty())
        assertEquals(PictureStep.Sending, sender.step)
        assertEquals(1, outbox.sends.size)
    }

    @Test
    fun aFailedUploadSaysWhyAndKeepsEverything() {
        openAndType()
        sender.send()
        scope.runCurrent()
        outbox.uploads.removeFirst().completeExceptionally(IOException("offline"))
        scope.runCurrent()
        assertEquals(PictureStep.UploadFailed(UploadFailure.NoConnection), sender.step)
        assertEquals("look at this", sender.text.text)
        assertTrue(outbox.remembered.isEmpty())

        // Another site may be tried; then Send uploads anew.
        sender.selectUploader(Uploader.Catbox)
        assertEquals(PictureStep.Ready, sender.step)
        sender.send()
        scope.runCurrent()
        outbox.uploads.removeFirst().completeExceptionally(HttpException(502, "Bad gateway"))
        scope.runCurrent()
        assertEquals(PictureStep.UploadFailed(UploadFailure.SiteError), sender.step)
    }

    @Test
    fun cancellingAnUploadSendsNothing() {
        openAndType()
        sender.send()
        scope.runCurrent()
        sender.cancel()
        scope.runCurrent()
        assertEquals(PictureStep.Ready, sender.step)
        assertTrue(outbox.sends.isEmpty())
        assertTrue(sender.isOpen)
    }

    /** Once online, the picture stays where it is: the site cannot change any more. */
    @Test
    fun theSiteIsFixedOnceUploaded() {
        openAndType()
        sender.send()
        scope.runCurrent()
        outbox.uploads.removeFirst().complete(online)
        scope.runCurrent()
        outbox.sends.removeFirst().second.complete(SendResult.RateLimited)
        scope.runCurrent()
        sender.selectUploader(Uploader.Catbox)
        assertEquals(Uploader.KappaLol, sender.uploader)
    }

    @Test
    fun anOpenPictureComesBackAfterTheAppWasEnded() {
        val file = File.createTempFile("picture", ".png").apply { deleteOnExit() }
        sender.open(Picture(file, "image/png"), "xqc", Uploader.Catbox)
        sender.text = TextFieldValue("hi")
        val saved = sender.save()

        val again = PictureSender(scope, ControlledOutbox()) {}
        again.restore(saved!!)
        assertEquals(file, again.picture?.file)
        assertEquals("hi", again.text.text)
        assertEquals("xqc", again.channel)
        assertEquals(Uploader.Catbox, again.uploader)
        assertNull(again.uploaded)
    }

    @Test
    fun nothingIsSavedWithoutPicture() {
        assertNull(sender.save())
    }
}
