package me.lesh.chatter.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.lesh.chatter.chat.SendResult
import me.lesh.chatter.net.AppJson
import me.lesh.chatter.net.HttpException
import me.lesh.chatter.upload.Deletion
import me.lesh.chatter.upload.NoLinkException
import me.lesh.chatter.upload.Picture
import me.lesh.chatter.upload.PictureTooLargeException
import me.lesh.chatter.upload.UploadedPicture
import me.lesh.chatter.upload.UploadTarget
import java.io.File

/** What the send screen needs from the app: the upload site, the list of uploads and the chat. */
internal interface PictureOutbox {
    suspend fun upload(picture: Picture, target: UploadTarget, onProgress: (Float) -> Unit): UploadedPicture

    /** Keeps an upload under Settings → Links & images, even when the message is never sent. */
    suspend fun remember(picture: Picture, uploaded: UploadedPicture, target: UploadTarget)

    suspend fun send(channel: String, text: String): SendResult

    /** Tells the user why a message did not go out. */
    suspend fun report(result: SendResult)

    /** Lets go of the copied picture once the screen closes. */
    fun discard(picture: Picture)
}

/** Where the send screen is; [Ready] until Send is tapped. */
internal sealed interface PictureStep {
    data object Ready : PictureStep

    /** [progress] is the share of the picture sent, 0 to 1. */
    data class Uploading(val progress: Float) : PictureStep
    data object Sending : PictureStep
    data class UploadFailed(val reason: UploadFailure) : PictureStep

    /** Uploaded, but the chat did not take the message; Send tries the message alone again. */
    data object NotSent : PictureStep
}

internal enum class UploadFailure { NoConnection, SiteError, TooLarge }

/**
 * The picture being sent, the message going with it, the channel and the upload site. One tap on
 * Send uploads and then sends; [UploadedPicture] is kept once there, so trying again after the
 * chat refused never uploads the same picture twice.
 *
 * [onSent] gets the channel once the chat took the message; the screen then closes.
 */
internal class PictureSender(
    private val scope: CoroutineScope,
    private val outbox: PictureOutbox,
    private val onSent: (String) -> Unit,
) {
    var picture by mutableStateOf<Picture?>(null)
        private set
    var text by mutableStateOf(TextFieldValue(""))
    var channel by mutableStateOf<String?>(null)
        private set
    /** Where the picture goes; null only while no site can take it. */
    var uploader by mutableStateOf<UploadTarget?>(null)
        private set
    var step by mutableStateOf<PictureStep>(PictureStep.Ready)
        private set
    var uploaded by mutableStateOf<UploadedPicture?>(null)
        private set

    private var job: Job? = null

    val isOpen: Boolean get() = picture != null
    val isBusy: Boolean get() = step is PictureStep.Uploading || step == PictureStep.Sending

    /** Starts over with [picture]; a picture already open is let go. */
    fun open(picture: Picture, channel: String?, uploader: UploadTarget?) {
        close()
        this.picture = picture
        this.channel = channel
        this.uploader = uploader
    }

    fun selectChannel(login: String) {
        if (!isBusy) channel = login
    }

    /** Another site for this picture only; not once it is uploaded. */
    fun selectUploader(choice: UploadTarget) {
        if (!isBusy && uploaded == null) {
            uploader = choice
            if (step is PictureStep.UploadFailed) step = PictureStep.Ready
        }
    }

    fun send() {
        val picture = picture ?: return
        val channel = channel ?: return
        val target = uploader ?: return
        if (isBusy) return
        job = scope.launch {
            val link = uploaded ?: upload(picture, target) ?: return@launch
            step = PictureStep.Sending
            val result = outbox.send(channel, messageWith(link.link))
            if (result == SendResult.Ok) {
                // Cleared first, so closing does not cancel this very send.
                job = null
                close()
                onSent(channel)
            } else {
                step = PictureStep.NotSent
                outbox.report(result)
            }
        }
    }

    /** Null after a failure, which [step] then names. */
    private suspend fun upload(picture: Picture, target: UploadTarget): UploadedPicture? {
        step = PictureStep.Uploading(0f)
        val done = try {
            outbox.upload(picture, target) { step = PictureStep.Uploading(it.coerceIn(0f, 1f)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            step = PictureStep.UploadFailed(
                when (e) {
                    is PictureTooLargeException -> UploadFailure.TooLarge
                    is HttpException, is NoLinkException -> UploadFailure.SiteError
                    else -> UploadFailure.NoConnection
                },
            )
            return null
        }
        uploaded = done
        // A cancel right now must not lose a picture that is already online.
        withContext(NonCancellable) { outbox.remember(picture, done, target) }
        return done
    }

    /** The typed text with the link after it. */
    fun messageWith(link: String): String = listOf(text.text.trim(), link).filter { it.isNotEmpty() }.joinToString(" ")

    /** Stops an upload or send on its way; what was uploaded stays, for Send to use. */
    fun cancel() {
        job?.cancel()
        job = null
        step = if (uploaded != null) PictureStep.NotSent else PictureStep.Ready
    }

    fun close() {
        job?.cancel()
        job = null
        picture?.let(outbox::discard)
        picture = null
        text = TextFieldValue("")
        channel = null
        uploaded = null
        step = PictureStep.Ready
    }

    /** The open picture as text, for when Android ends the app in the background; null if none. */
    fun save(): String? {
        val picture = picture ?: return null
        return AppJson.encodeToString(
            SavedPicture(picture.file.path, picture.type, text.text, channel, uploader?.id, uploaded?.link, uploaded?.deletion),
        )
    }

    /** Takes back what [save] wrote; [site] finds the upload site by its id, if it is still there. */
    fun restore(saved: String, site: (String) -> UploadTarget?) {
        val restored = runCatching { AppJson.decodeFromString<SavedPicture>(saved) }.getOrNull() ?: return
        val file = File(restored.path).takeIf { it.isFile } ?: return
        picture = Picture(file, restored.type)
        text = TextFieldValue(restored.text, TextRange(restored.text.length))
        channel = restored.channel
        uploader = restored.uploader?.let(site)
        uploaded = restored.link?.let { UploadedPicture(it, restored.deletion) }
        step = if (uploaded != null) PictureStep.NotSent else PictureStep.Ready
    }
}

@Serializable
internal class SavedPicture(
    val path: String,
    val type: String,
    val text: String,
    val channel: String?,
    val uploader: String?,
    val link: String? = null,
    val deletion: Deletion? = null,
)
