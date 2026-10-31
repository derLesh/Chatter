package me.lesh.chatter.storage

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Process
import android.os.storage.StorageManager
import android.util.Log
import coil3.disk.DiskCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache

/** What Chatter keeps on the phone, in bytes. Null where Android did not say. */
data class StorageReport(
    val images: Long,
    val emoteLists: Long,
    /** Settings, channels, inbox and stats: everything clearing the cache keeps. */
    val saved: Long?,
    val app: Long?,
)

/**
 * Measures what Chatter stores and clears what it can download again: the pictures and the
 * emote and badge lists.
 */
class StorageUsage(
    private val context: Context,
    private val images: () -> DiskCache,
    private val emoteLists: Cache?,
) {
    suspend fun measure(): StorageReport = withContext(Dispatchers.IO) {
        // An app may ask about itself without a permission.
        val stats = runCatching {
            context.getSystemService(StorageStatsManager::class.java)
                .queryStatsForPackage(StorageManager.UUID_DEFAULT, context.packageName, Process.myUserHandle())
        }.onFailure { Log.w(TAG, "Android did not say how much space Chatter takes", it) }.getOrNull()
        StorageReport(
            images = runCatching { images().size }.getOrDefault(0L),
            emoteLists = runCatching { emoteLists?.size() }.getOrNull() ?: 0L,
            saved = stats?.let { it.dataBytes - it.cacheBytes },
            app = stats?.appBytes,
        )
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        runCatching { images().clear() }.onFailure { Log.w(TAG, "Could not clear the image cache", it) }
        runCatching { emoteLists?.evictAll() }.onFailure { Log.w(TAG, "Could not clear the list cache", it) }
    }

    private companion object {
        const val TAG = "StorageUsage"
    }
}
