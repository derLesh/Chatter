package dev.chatter.app.assistant

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.appfunctions.AppFunctionManager
import kotlinx.coroutines.CancellationException

/**
 * Enables the functions in [ChatterFunctions] exactly while the beta switch is on. Android keeps the
 * state per install, so it is set again on every start; a reinstall or a restored backup would
 * otherwise leave it at the declared default, off.
 */
class AssistantAccess(private val context: Context) {
    suspend fun allow(allowed: Boolean) {
        // AppFunctions came with Android 16.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
        // Null where the system has no AppFunctions service, as on some Android 16 builds.
        val manager = AppFunctionManager.getInstance(context) ?: return
        val state = if (allowed) AppFunctionManager.APP_FUNCTION_STATE_ENABLED else AppFunctionManager.APP_FUNCTION_STATE_DISABLED
        for (function in FUNCTIONS) {
            try {
                manager.setAppFunctionEnabled(function, state)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not switch $function", e)
            }
        }
    }

    private companion object {
        const val TAG = "AssistantAccess"

        /**
         * Android names a function by the class declaring it and its name. Spelled out: a class
         * literal of [ChatterFunctions] would load an Android 16 class on older phones.
         */
        val FUNCTIONS = listOf("listChannels", "listMentions", "openChannel", "sendChatMessage")
            .map { "dev.chatter.app.assistant.ChatterFunctions#$it" }
    }
}
