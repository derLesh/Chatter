package me.lesh.chatter.storage

import me.lesh.chatter.ui.recap.SHARED_FOLDER
import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Process
import android.os.storage.StorageManager
import me.lesh.chatter.chat.BotCommands
import me.lesh.chatter.debug.AppLog
import me.lesh.chatter.emotes.EmoteListFiles
import coil3.disk.DiskCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache

/** What Chatter keeps on the phone, in bytes. Null where Android did not say. */
data class StorageReport(
    val images: Long,
    val emoteLists: Long,
    val botCommands: Long,
    /** Settings, channels, inbox and stats: everything clearing the cache keeps. */
    val saved: Long?,
    /** The log, its earlier days and the crashes; part of [saved]. */
    val logs: Long,
    val app: Long?,
)

/**
 * Measures what Chatter stores and clears what it can download again: the pictures, the emote and
 * badge lists and the bots' commands.
 */
class StorageUsage(
    private val context: Context,
    private val images: () -> DiskCache,
    private val emoteLists: Cache?,
    private val keptEmoteLists: EmoteListFiles,
    private val botCommands: BotCommands,
) {
    suspend fun measure(): StorageReport = withContext(Dispatchers.IO) {
        // An app may ask about itself without a permission.
        val stats = runCatching {
            context.getSystemService(StorageStatsManager::class.java)
                .queryStatsForPackage(StorageManager.UUID_DEFAULT, context.packageName, Process.myUserHandle())
        }.onFailure { AppLog.w(TAG, "Android did not say how much space Chatter takes", it) }.getOrNull()
        StorageReport(
            // The shared pictures of the look back at the year count as pictures too.
            images = runCatching { images().size }.getOrDefault(0L) +
                context.cacheDir.resolve(SHARED_FOLDER).walk().filter { it.isFile }.sumOf { it.length() },
            emoteLists = (runCatching { emoteLists?.size() }.getOrNull() ?: 0L) + runCatching { keptEmoteLists.size() }.getOrDefault(0L),
            botCommands = runCatching { botCommands.size() }.getOrDefault(0L),
            saved = stats?.let { it.dataBytes - it.cacheBytes },
            logs = LOG_DIRS.sumOf { name -> context.filesDir.resolve(name).walk().filter { it.isFile }.sumOf { it.length() } },
            app = stats?.appBytes,
        )
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        runCatching { images().clear() }.onFailure { AppLog.w(TAG, "Could not clear the image cache", it) }
        runCatching { emoteLists?.evictAll() }.onFailure { AppLog.w(TAG, "Could not clear the list cache", it) }
        keptEmoteLists.clear()
        botCommands.forget()
        // Pictures of the look back at the year that were shared.
        context.cacheDir.resolve(SHARED_FOLDER).deleteRecursively()
    }

    private companion object {
        const val TAG = "StorageUsage"

        /** Where `ChatterApp` keeps the log and the crashes. */
        val LOG_DIRS = listOf("log", "crashes")
    }
}
