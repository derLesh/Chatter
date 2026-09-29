package dev.chatter.app.update

import android.util.Log
import dev.chatter.app.changelog.Version
import dev.chatter.app.net.AppJson
import dev.chatter.app.net.DataSaving
import dev.chatter.app.net.fetch
import dev.chatter.app.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Whether a newer Chatter is out, for the APK people install from GitHub, which nothing else ever
 * tells about one. Play updates its own installs and does not let an app point anywhere else for
 * them, so a build for Play is made with [enabled] false and never asks.
 *
 * GitHub is asked at most once a day, and only while the app is opened; what it said is kept on
 * the device until the next time. A check that fails is not counted, so the next start tries again.
 */
class UpdateRepository(
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    versionName: String,
    private val enabled: Boolean,
    /** While this is true the check waits: a release can wait for Wi-Fi, see [DataSaving]. */
    private val saveData: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val installed = Version.parse(versionName)

    /** The newer release while the user wants to hear about one, and null the rest of the time. */
    val available: StateFlow<AvailableUpdate?> = combine(
        settings.settings.map { it.updateCheck },
        settings.availableUpdate,
    ) { on, stored ->
        val update = stored?.let { runCatching { AppJson.decodeFromString<AvailableUpdate>(it) }.getOrNull() }
        // Once the new version is installed, the stored one is no longer newer and the news is gone.
        update?.takeIf { enabled && on && UpdateCheck.isNewer(it, installed) }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private var checking: Job? = null

    /** Asks GitHub for the latest release, unless that was done less than a day ago. */
    fun checkIfDue() {
        if (!enabled || checking?.isActive == true) return
        checking = scope.launch {
            if (!settings.settings.first().updateCheck || saveData.value) return@launch
            val now = clock()
            if (!UpdateCheck.isDue(settings.updateCheckedAt.first(), now)) return@launch
            val request = Request.Builder()
                .url(UpdateCheck.LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
            val json = runCatching { http.fetch(request) }.getOrElse {
                // Nothing the user asked for failed, so there is nothing to put on the screen.
                Log.i(TAG, "Could not ask GitHub for the latest release", it)
                return@launch
            }
            val update = UpdateCheck.parse(json) ?: return@launch
            settings.setAvailableUpdate(AppJson.encodeToString(AvailableUpdate.serializer(), update), now)
        }
    }

    private companion object {
        const val TAG = "UpdateRepository"
    }
}
