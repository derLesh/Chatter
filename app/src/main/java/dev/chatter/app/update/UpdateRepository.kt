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
 * Checks for a newer release, for the APK installed from GitHub. Play updates its own installs and
 * forbids pointing elsewhere, so Play builds have [enabled] false.
 *
 * Asks at most once a day while the app is open and stores the answer. A failed check does not
 * count, so the next start tries again.
 */
class UpdateRepository(
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    versionName: String,
    private val enabled: Boolean,
    /** While true the check waits; see [DataSaving]. */
    private val saveData: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val installed = Version.parse(versionName)

    /** The newer release if the user wants to hear about it, otherwise null. */
    val available: StateFlow<AvailableUpdate?> = combine(
        settings.settings.map { it.updateCheck },
        settings.availableUpdate,
    ) { on, stored ->
        val update = stored?.let { runCatching { AppJson.decodeFromString<AvailableUpdate>(it) }.getOrNull() }
        // After installing it, the stored release is no longer newer.
        update?.takeIf { enabled && on && UpdateCheck.isNewer(it, installed) }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private var checking: Job? = null

    /** Asks GitHub unless that was done less than a day ago. */
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
                // The user did not ask for this, so nothing goes on screen.
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
