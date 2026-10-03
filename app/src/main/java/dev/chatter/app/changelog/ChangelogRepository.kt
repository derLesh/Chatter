package dev.chatter.app.changelog

import android.content.Context
import dev.chatter.app.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The bundled changelog and how much of it the user has read. Shown in the settings, and after an
 * update as the releases the user missed, but only for minor and major versions.
 */
class ChangelogRepository(
    private val context: Context,
    private val settings: SettingsRepository,
    private val versionName: String,
    private val scope: CoroutineScope,
) {
    private val _releases = MutableStateFlow<List<Release>>(emptyList())
    /** All known releases, newest first. */
    val releases: StateFlow<List<Release>> = _releases.asStateFlow()

    private val _unread = MutableStateFlow<List<Release>>(emptyList())
    /** Releases to show after an update; empty otherwise. */
    val unread: StateFlow<List<Release>> = _unread.asStateFlow()

    val version: Version? = Version.parse(versionName)

    fun start() {
        scope.launch {
            _releases.value = withContext(Dispatchers.IO) { read() }
            _unread.value = unreadReleases()
        }
    }

    /** Marks the current version as read. */
    fun markRead() {
        _unread.value = emptyList()
        scope.launch { settings.setSeenVersion(versionName) }
    }

    private fun read(): List<Release> = runCatching {
        context.assets.open(ASSET).bufferedReader().use { ChangelogParser.parse(it.readText()) }
    }.getOrDefault(emptyList())

    private suspend fun unreadReleases(): List<Release> {
        val current = version ?: return emptyList()
        val seen = settings.seenVersion.first()?.let { Version.parse(it) }
        if (seen == null) {
            // Nothing recorded yet. A fresh install has nothing to report; an update from a version
            // that did not track this shows the current release.
            if (isFirstInstall()) {
                settings.setSeenVersion(versionName)
                return emptyList()
            }
            return _releases.value.filter { it.version == current }
        }
        // Stays unread on a patch release, so the notes come with the next bigger one.
        if (seen.isOnlyAFixAwayFrom(current)) return emptyList()
        return _releases.value.filter { it.version > seen }
    }

    /** Android tells install and update apart by these two timestamps. */
    private fun isFirstInstall(): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.firstInstallTime == info.lastUpdateTime
    }.getOrDefault(true)

    private companion object {
        /** Copied from CHANGELOG.md by the copyChangelog task. */
        const val ASSET = "changelog.md"
    }
}
