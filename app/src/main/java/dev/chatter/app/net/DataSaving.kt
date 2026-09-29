package dev.chatter.app.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import dev.chatter.app.settings.MobileData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Whether Chatter should spend as little data as it can right now: on a metered network, while
 * Android's Data Saver is on or the user chose [MobileData.SaveData] for Chatter alone.
 *
 * What this holds back is what the chat can do without — pictures somebody linked, animation,
 * sharp emotes, the update check. The chat connection itself is never touched: messages, mentions
 * and whispers are what the app is for, and they are a few bytes each.
 *
 * Data Saver only ever applies to metered networks, so a phone with it switched on still gets
 * everything on Wi-Fi.
 */
class DataSaving(context: Context, mobileData: Flow<MobileData>, scope: CoroutineScope) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val metered = MutableStateFlow(connectivity.isActiveNetworkMetered)
    private val dataSaver = MutableStateFlow(readDataSaver())

    val active: StateFlow<Boolean> = combine(metered, dataSaver, mobileData) { metered, saver, choice ->
        metered && (saver || choice == MobileData.SaveData)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        // Only ever sent to receivers registered at runtime, which is why this is not in the manifest.
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    dataSaver.value = readDataSaver()
                }
            },
            IntentFilter(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED),
        )
    }

    /**
     * The default network changed, or what it costs did. Called from the network callback that
     * also tells the chat connection about it; asks rather than trusting the callback's own network,
     * for the same reason that one does.
     */
    fun networkChanged() {
        metered.value = connectivity.isActiveNetworkMetered
        dataSaver.value = readDataSaver()
    }

    /** True if the user put Chatter under Data Saver: switched it on and did not exempt the app. */
    private fun readDataSaver(): Boolean =
        connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
}
