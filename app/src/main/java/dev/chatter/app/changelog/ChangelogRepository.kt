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
 * The changelog as it ships with the app, and how much of it the user has already read.
 *
 * It shows up in two places: the whole list under the settings, and — right after an update — the
 * releases the user missed. That second one only happens for a new minor or major version, so a
 * release that only fixes things never puts a sheet in anyone's way.
 */
class ChangelogRepository(
    private val context: Context,
    private val settings: SettingsRepository,
    private val versionName: String,
    private val scope: CoroutineScope,
) {
    private val _releases = MutableStateFlow<List<Release>>(emptyList())
    /** Every release the app knows about, newest first. */
    val releases: StateFlow<List<Release>> = _releases.asStateFlow()

    private val _unread = MutableStateFlow<List<Release>>(emptyList())
    /** What to show after an update, and nothing at all the rest of the time. */
    val unread: StateFlow<List<Release>> = _unread.asStateFlow()

    val version: Version? = Version.parse(versionName)

    fun start() {
        scope.launch {
            _releases.value = withContext(Dispatchers.IO) { read() }
            _unread.value = unreadReleases()
        }
    }

    /** Once the user has seen them, this version counts as read and the notes stay gone. */
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
            // Nothing was ever recorded. On a fresh install there is no update to report, so the
            // user starts out having read everything; otherwise they updated from a version that
            // did not keep track yet, and the release they just got is the news.
            if (isFirstInstall()) {
                settings.setSeenVersion(versionName)
                return emptyList()
            }
            return _releases.value.filter { it.version == current }
        }
        // Left unread on a fix release, so those notes still arrive with the next real one.
        if (seen.isOnlyAFixAwayFrom(current)) return emptyList()
        return _releases.value.filter { it.version > seen }
    }

    /** Freshly installed rather than updated, which Android tells apart by these two timestamps. */
    private fun isFirstInstall(): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.firstInstallTime == info.lastUpdateTime
    }.getOrDefault(true)

    private companion object {
        /** Written into the assets from the repo's CHANGELOG.md by the "copyChangelog" task. */
        const val ASSET = "changelog.md"
    }
}
