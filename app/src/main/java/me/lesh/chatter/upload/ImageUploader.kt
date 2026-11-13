package me.lesh.chatter.upload

import android.content.ContentResolver
import android.net.Uri
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.lesh.chatter.debug.AppLog
import me.lesh.chatter.net.fetch
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class PictureTooLargeException : IOException("The picture is larger than ${ImageUploader.MAX_BYTES} bytes")

/** The site answered, but with no link in it. */
class NoLinkException : IOException("The answer holds no link")

/** A picture copied into the app, so it outlives the permission of the app that shared it. */
data class Picture(val file: File, val type: String)

/** Copies pictures in, uploads them, and deletes them from a site again. */
class ImageUploader(http: OkHttpClient, private val resolver: ContentResolver, private val dir: File) {
    // A photo is a few megabytes, and the phone's upload can be slow.
    private val http = http.newBuilder().writeTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    /**
     * Copies [uri] into the app. The permission to read a shared picture ends with the activity
     * that got it, and the picture has to survive a login or the app being closed in between.
     */
    suspend fun copy(uri: Uri): Picture = withContext(Dispatchers.IO) {
        val type = resolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/png"
        dir.mkdirs()
        val file = File(dir, UUID.randomUUID().toString())
        val input = resolver.openInputStream(uri) ?: throw IOException("The picture cannot be opened")
        try {
            input.use { source ->
                file.outputStream().use { target ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        copied += read
                        // Stops one buffer past the limit, without reading all of a huge file.
                        if (copied > MAX_BYTES) throw PictureTooLargeException()
                        target.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: IOException) {
            file.delete()
            throw e
        }
        Picture(file, type)
    }

    /** Forgets [picture] once it is sent or the screen is closed. */
    fun discard(picture: Picture) {
        picture.file.delete()
    }

    /** Deletes the copies an app ended mid-send left behind, all but the one still open. */
    suspend fun discardAllBut(open: Picture?) = withContext(Dispatchers.IO) {
        dir.listFiles()?.filter { it != open?.file }?.forEach { it.delete() }
    }

    /** Uploads [picture] to [uploader]; [onProgress] gets the share sent so far, 0 to 1. */
    suspend fun upload(picture: Picture, uploader: Uploader, custom: UploadHost, onProgress: (Float) -> Unit): UploadedPicture {
        val host = uploader.host(custom)
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(picture.type) ?: "png"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            host.extraFields.forEach { (name, value) -> addFormDataPart(name, value) }
            addFormDataPart(host.field, "image.$extension", ProgressBody(picture.file, picture.type.toMediaType(), onProgress))
        }.build()
        // A site of the user's own is named only in the full log; it may say whose it is.
        val site = if (uploader == Uploader.Custom) AppLog.hidden(uploader.name(custom), "own site") else uploader.title
        AppLog.i(TAG, "Uploading a picture of ${picture.file.length() / 1024} KB to $site")
        val answer = http.fetch(Request.Builder().url(host.url).post(body).build())
        val link = UploadAnswer.link(answer, host.linkKey) ?: throw NoLinkException()
        AppLog.i(TAG, "Uploaded to $site")
        return UploadedPicture(link, uploader.deletion(answer, host))
    }

    /** Asks the site to delete a picture; true once it says it did. Never for [Deletion.inBrowser]. */
    suspend fun delete(deletion: Deletion): Boolean {
        check(!deletion.inBrowser) { "A deletion page is opened in the browser" }
        return UploadAnswer.deleted(http.fetch(Request.Builder().url(deletion.url).build()))
    }

    /** Sends [file] and counts what has gone out, for the pill on the send screen. */
    private class ProgressBody(private val file: File, private val type: MediaType, private val onProgress: (Float) -> Unit) : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = file.length()

        override fun writeTo(sink: BufferedSink) {
            val total = contentLength().coerceAtLeast(1)
            var sent = 0L
            file.source().use { source ->
                while (true) {
                    val read = source.read(sink.buffer, SEGMENT)
                    if (read == -1L) break
                    sink.flush()
                    sent += read
                    onProgress(sent.toFloat() / total)
                }
            }
        }
    }

    companion object {
        private const val TAG = "Upload"

        /** 20 MB, what the common sites take. */
        const val MAX_BYTES = 20 * 1024 * 1024
        private const val SEGMENT = 64 * 1024L
    }
}
