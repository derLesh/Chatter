package me.lesh.chatter.upload

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
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
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
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
     * With [removeInfo] it is copied without what tells where, when and with what it was taken;
     * a picture that cannot be cleaned is refused rather than sent with it.
     */
    suspend fun copy(uri: Uri, removeInfo: Boolean): Picture = withContext(Dispatchers.IO) {
        val original = read(uri)
        val given = resolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/png"
        val (bytes, type) = if (removeInfo) withoutInfo(original) else original to given
        if (bytes.size > MAX_BYTES) throw PictureTooLargeException()
        dir.mkdirs()
        val file = File(dir, UUID.randomUUID().toString())
        try {
            file.writeBytes(bytes)
        } catch (e: IOException) {
            file.delete()
            throw e
        }
        Picture(file, type)
    }

    private fun read(uri: Uri): ByteArray {
        val input = resolver.openInputStream(uri) ?: throw IOException("The picture cannot be opened")
        return input.use { source ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                // Stops one buffer past the limit, without reading all of a huge file.
                if (out.size() + read > MAX_BYTES) throw PictureTooLargeException()
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }

    /**
     * [bytes] without their metadata, and the format they are in. The format is read from the
     * picture itself, not from what the sharing app said. JPEG, PNG, WebP and GIF are cleaned as
     * they are; anything else, HEIC for one, is drawn anew as a JPEG, which keeps none of it.
     */
    private fun withoutInfo(bytes: ByteArray): Pair<ByteArray, String> {
        val type = PictureCleaner.typeOf(bytes)
        type?.let { PictureCleaner.clean(bytes, it) }?.let { return it to type }
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, REDRAWN_QUALITY, out)
        bitmap.recycle()
        return out.toByteArray() to "image/jpeg"
    }

    /** Forgets [picture] once it is sent or the screen is closed. */
    fun discard(picture: Picture) {
        picture.file.delete()
    }

    /** Deletes the copies an app ended mid-send left behind, all but the one still open. */
    suspend fun discardAllBut(open: Picture?) = withContext(Dispatchers.IO) {
        dir.listFiles()?.filter { it != open?.file }?.forEach { it.delete() }
    }

    /** Uploads [picture] to [target]; [onProgress] gets the share sent so far, 0 to 1. */
    suspend fun upload(picture: Picture, target: UploadTarget, onProgress: (Float) -> Unit): UploadedPicture {
        val host = target.host
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(picture.type) ?: "png"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            host.extraFields.forEach { (name, value) -> addFormDataPart(name, value) }
            addFormDataPart(host.field, "image.$extension", ProgressBody(picture.file, picture.type.toMediaType(), onProgress))
        }.build()
        // A site of the user's own is named only in the full log; it may say whose it is.
        val site = if (target.isCustom) AppLog.hidden(target.name, "own site") else target.name
        AppLog.i(TAG, "Uploading a picture of ${picture.file.length() / 1024} KB to $site")
        val request = Request.Builder().url(host.url).post(body).apply { host.headers.forEach { (name, value) -> header(name, value) } }
        val answer = http.fetch(request.build())
        val link = UploadAnswer.link(answer, host.linkKey) ?: throw NoLinkException()
        AppLog.i(TAG, "Uploaded to $site")
        return UploadedPicture(link, target.deletion(answer, link))
    }

    /** Asks the site to delete a picture; true once it says it did. Never for [Deletion.inBrowser]. */
    suspend fun delete(deletion: Deletion): Boolean {
        check(!deletion.inBrowser) { "A deletion page is opened in the browser" }
        val form = deletion.form.takeIf { it.isNotEmpty() }?.let { fields ->
            MultipartBody.Builder().setType(MultipartBody.FORM).apply { fields.forEach { (name, value) -> addFormDataPart(name, value) } }.build()
        }
        val request = Request.Builder().url(deletion.url)
            .method(deletion.method, form ?: if (deletion.method == "GET") null else EMPTY_BODY)
            .apply { deletion.headers.forEach { (name, value) -> header(name, value) } }
        return UploadAnswer.deleted(http.fetch(request.build()))
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
        private const val REDRAWN_QUALITY = 92
        private val EMPTY_BODY = ByteArray(0).toRequestBody()
    }
}
