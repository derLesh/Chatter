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
 * Whether Chatter should save data right now: on a metered network with Android's Data Saver on, or
 * when the user chose [MobileData.SaveData] for Chatter.
 *
 * This holds back linked pictures, animation, sharp emotes and the update check. The chat
 * connection is never affected; messages are a few bytes each.
 */
class DataSaving(context: Context, mobileData: Flow<MobileData>, scope: CoroutineScope) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val metered = MutableStateFlow(connectivity.isActiveNetworkMetered)
    private val dataSaver = MutableStateFlow(readDataSaver())

    val active: StateFlow<Boolean> = combine(metered, dataSaver, mobileData) { metered, saver, choice ->
        metered && (saver || choice == MobileData.SaveData)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        // Only delivered to receivers registered at runtime.
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
     * The default network or its cost changed. Asks the system for the current state instead of
     * trusting the callback's network, like the chat connection does.
     */
    fun networkChanged() {
        metered.value = connectivity.isActiveNetworkMetered
        dataSaver.value = readDataSaver()
    }

    /** True if Data Saver is on and Chatter is not exempted. */
    private fun readDataSaver(): Boolean =
        connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
}
