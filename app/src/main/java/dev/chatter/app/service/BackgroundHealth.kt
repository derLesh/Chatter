package dev.chatter.app.service

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chatter.app.net.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Why the process was ended from outside while listening, as the card names it. */
enum class StopReason {
    /** The phone ran short of memory. */
    LowMemory,

    /** Android found the app using too much CPU or battery in the background. */
    ResourceUse,

    /** Force-stopped from the app's system settings, or from a task manager. */
    UserRequest,

    /** Ended for a reason Android does not name; most vendor battery savers look like this. */
    System,
}

/** The last time the background connection was ended from outside, and why. */
@Serializable
data class BackgroundStop(val at: Long, val reason: StopReason)

/**
 * The parts of an [ApplicationExitInfo] that matter here, so the rules are testable without one.
 */
data class ProcessExit(val at: Long, val reason: Int, val listening: Boolean)

/** Which process ends were caused from outside while mentions were being listened for. */
object BackgroundStops {
    /** The latest of [exits] after [after] that ended a listening process from outside, if any. */
    fun latest(exits: List<ProcessExit>, after: Long): BackgroundStop? =
        exits.asSequence()
            .filter { it.at > after && it.listening }
            .mapNotNull { exit -> reasonOf(exit.reason)?.let { BackgroundStop(exit.at, it) } }
            .maxByOrNull { it.at }

    /**
     * Null for ends that have nothing to do with battery or memory: the app exiting, a crash, an
     * ANR, an update, a revoked permission. The battery settings fix none of them.
     */
    fun reasonOf(reason: Int): StopReason? = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> StopReason.LowMemory
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> StopReason.ResourceUse
        ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> StopReason.UserRequest
        ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_OTHER -> StopReason.System
        else -> null
    }
}

/** Chatter's battery settings, read on demand. */
data class BatteryRestrictions(
    /** Battery use is Restricted: Android stops the service whenever it likes. */
    val restricted: Boolean = false,
    /** Battery use is Optimized (the default); enough to be stopped on some phones. */
    val optimized: Boolean = false,
)

/**
 * Whether Android lets the background connection run, and when it last stopped it.
 *
 * Mentions only arrive while [ChatService] runs. Some phones stop it anyway, and restricted battery
 * use does the same; mentions then stop without any sign in the app. Everything here is read from
 * the phone and stays on it.
 */
class BackgroundHealth(
    private val context: Context,
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    private val activity = context.getSystemService(ActivityManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    /** The last stop not yet dismissed. */
    val lastStop: StateFlow<BackgroundStop?> = store.data
        .map { p -> p[LAST_STOP]?.let { runCatching { AppJson.decodeFromString<BackgroundStop>(it) }.getOrNull() } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _restrictions = MutableStateFlow(readRestrictions())
    val restrictions: StateFlow<BatteryRestrictions> = _restrictions

    /** Checks how previous processes ended; only possible once per process start. */
    fun start() {
        scope.launch(Dispatchers.IO) { runCatching { lookBack() }.onFailure { Log.w(TAG, "Could not read exit reasons", it) } }
    }

    /** Reads the battery settings again, e.g. after the user returns from changing them. */
    fun refresh() {
        _restrictions.value = readRestrictions()
    }

    /**
     * Marks the process as listening or not. Android keeps the mark with the process and returns it
     * with the exit reason, which [lookBack] reads on the next start.
     */
    fun setListening(listening: Boolean) {
        runCatching { activity.setProcessStateSummary(byteArrayOf(if (listening) 1 else 0)) }
    }

    fun dismiss() {
        scope.launch { store.edit { it.remove(LAST_STOP) } }
    }

    private suspend fun lookBack() {
        val now = System.currentTimeMillis()
        val checked = store.data.first()[CHECKED_UNTIL] ?: 0L
        // Android keeps these for days; old ones say little about now.
        val after = maxOf(checked, now - MAX_AGE_MS)
        val exits = activity.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
            .map { ProcessExit(it.timestamp, it.reason, wasListening(it)) }
        val stop = BackgroundStops.latest(exits, after)
        store.edit { p ->
            p[CHECKED_UNTIL] = maxOf(checked, exits.maxOfOrNull { it.at } ?: 0L)
            if (stop != null) p[LAST_STOP] = AppJson.encodeToString(BackgroundStop.serializer(), stop)
        }
    }

    /**
     * The last [setListening] state. Processes from before the mark existed are judged by their
     * importance: a foreground service only runs for this.
     */
    private fun wasListening(exit: ApplicationExitInfo): Boolean {
        val summary = exit.processStateSummary
        if (summary != null && summary.isNotEmpty()) return summary[0] == 1.toByte()
        return exit.importance <= RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
    }

    private fun readRestrictions() = BatteryRestrictions(
        restricted = activity.isBackgroundRestricted,
        optimized = !power.isIgnoringBatteryOptimizations(context.packageName),
    )

    private companion object {
        const val TAG = "BackgroundHealth"
        const val MAX_EXITS = 16
        const val MAX_AGE_MS = 7 * 24 * 60 * 60_000L
        val LAST_STOP = stringPreferencesKey("last_background_stop")
        val CHECKED_UNTIL = longPreferencesKey("exits_checked_until")
    }
}
