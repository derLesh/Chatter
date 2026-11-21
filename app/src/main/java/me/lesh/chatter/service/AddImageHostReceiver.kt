package me.lesh.chatter.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import me.lesh.chatter.ChatterApp
import me.lesh.chatter.R
import me.lesh.chatter.chat.ImageLinks
import me.lesh.chatter.debug.AppLog
import me.lesh.chatter.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Adds the site of the page open in a link's tab to the sites linked images are loaded from; sent
 * by the tab's menu entry, with the page's address as data (see rememberLinkOpener).
 */
class AddImageHostReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val page = intent.dataString ?: return
        val app = context.applicationContext
        val container = (app as? ChatterApp)?.container ?: return
        val pending = goAsync()
        // Off the main thread: the first lookup reads the public suffix list from disk.
        container.scope.launch {
            try {
                val message = try {
                    addSourceOf(page, container.settings)?.let { (host, known) ->
                        app.getString(if (known) R.string.link_menu_image_host_known else R.string.link_menu_image_host_added, host)
                    } ?: app.getString(R.string.link_menu_image_host_refused)
                } catch (e: IOException) {
                    AppLog.w(TAG, "Image source not saved (${e.javaClass.simpleName})")
                    app.getString(R.string.link_menu_image_host_failed)
                }
                // Shows over the tab, which stays open.
                withContext(Dispatchers.Main) { Toast.makeText(app, message, Toast.LENGTH_SHORT).show() }
            } finally {
                pending.finish()
            }
        }
    }

    /** The host added for [page] and whether the list had it already; null for no image source. */
    private suspend fun addSourceOf(page: String, settings: SettingsRepository): Pair<String, Boolean>? {
        val host = ImageLinks.imageSourceOf(page) ?: return null
        var known = false
        settings.updateImageHosts { current ->
            // A parent site on the list covers its subdomains already.
            known = current.any { host == it || host.endsWith(".$it") }
            if (known) current else current + host
        }
        return host to known
    }

    private companion object {
        const val TAG = "AddImageHost"
    }
}
