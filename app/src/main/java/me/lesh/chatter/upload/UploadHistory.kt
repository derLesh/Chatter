package me.lesh.chatter.upload

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.lesh.chatter.net.AppJson
import java.io.File
import java.util.UUID

/** One picture the user uploaded, newest first in [UploadHistory.uploads]. [at] is in ms; [site] its name. */
@Serializable
data class RecentUpload(
    val id: String,
    val link: String,
    val site: String,
    val deletion: Deletion? = null,
    val at: Long,
)

/**
 * The last [MAX] pictures uploaded, each with a small preview, so they can be copied or deleted
 * again. It stays on this phone: the folder is outside the settings backup and the transfer to a
 * new phone (data_extraction_rules.xml lists only what moves), and a deletion link never goes into
 * the log, since it deletes the picture.
 */
class UploadHistory(
    private val dir: File,
    scope: CoroutineScope,
    private val makePreview: (Picture, File) -> Unit = ::writePreview,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _uploads = MutableStateFlow<List<RecentUpload>>(emptyList())
    val uploads: StateFlow<List<RecentUpload>> = _uploads.asStateFlow()
    private val lock = Mutex()
    private val file = File(dir, "history.json")

    init {
        scope.launch(io) {
            lock.withLock { loadOnce() }
        }
    }

    /** The small picture shown for [upload]; missing if it could not be made. */
    fun preview(upload: RecentUpload): File = File(dir, "${upload.id}.jpg")

    /** Keeps [uploaded], with a preview of [picture], and lets the oldest go past [MAX]. */
    suspend fun add(picture: Picture, uploaded: UploadedPicture, site: String): RecentUpload = withContext(io) {
        lock.withLock {
            loadOnce()
            dir.mkdirs()
            val upload = RecentUpload(UUID.randomUUID().toString(), uploaded.link, site, uploaded.deletion, now())
            // Without a preview the entry still holds the link, which is what matters.
            runCatching { makePreview(picture, preview(upload)) }
            val all = listOf(upload) + _uploads.value
            // A preview leaves with its entry; the picture on the site stays.
            all.drop(MAX).forEach { preview(it).delete() }
            save(all.take(MAX))
            upload
        }
    }

    /** Forgets [id], after it was deleted from its site. */
    suspend fun remove(id: String) = withContext(io) {
        lock.withLock {
            loadOnce()
            _uploads.value.firstOrNull { it.id == id }?.let { preview(it).delete() }
            save(_uploads.value.filterNot { it.id == id })
        }
    }

    private var loaded = false

    /** Reads the file before the first change, so an upload right after the start keeps the older ones. */
    private fun loadOnce() {
        if (loaded) return
        loaded = true
        _uploads.value = runCatching { AppJson.decodeFromString<List<RecentUpload>>(file.readText()) }.getOrDefault(emptyList())
    }

    private fun save(uploads: List<RecentUpload>) {
        _uploads.value = uploads
        file.writeText(AppJson.encodeToString(uploads))
    }

    companion object {
        const val MAX = 10
        private const val PREVIEW_PX = 192
        private const val PREVIEW_QUALITY = 80

        /** A JPEG of [picture] no larger than [PREVIEW_PX] on its longer side. */
        fun writePreview(picture: Picture, target: File) {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(picture.file)) { decoder, info, _ ->
                val longer = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
                val scale = minOf(1f, PREVIEW_PX.toFloat() / longer)
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, PREVIEW_QUALITY, it) }
            bitmap.recycle()
        }
    }
}
