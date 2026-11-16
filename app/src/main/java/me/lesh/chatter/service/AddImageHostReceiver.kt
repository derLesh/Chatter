package me.lesh.chatter.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import me.lesh.chatter.ChatterApp
import me.lesh.chatter.R
import me.lesh.chatter.chat.ImageLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Adds the site of the page open in a link's tab to the sites linked images are loaded from; sent
 * by the tab's menu entry, with the page's address as data (see rememberLinkOpener).
 */
class AddImageHostReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val host = intent.dataString?.toHttpUrlOrNull()?.host?.let(ImageLinks::cleanHost)?.takeIf { it.isNotEmpty() } ?: return
        val container = (context.applicationContext as? ChatterApp)?.container ?: return
        val app = context.applicationContext
        val pending = goAsync()
        container.scope.launch {
            try {
                var alreadyShown = false
                container.settings.updateImageHosts { current ->
                    // A parent site on the list covers its subdomains already.
                    alreadyShown = current.any { host == it || host.endsWith(".$it") }
                    if (alreadyShown) current else current + host
                }
                val message = if (alreadyShown) R.string.link_menu_image_host_known else R.string.link_menu_image_host_added
                // Shows over the tab, which stays open.
                withContext(Dispatchers.Main) { Toast.makeText(app, app.getString(message, host), Toast.LENGTH_SHORT).show() }
            } finally {
                pending.finish()
            }
        }
    }
}
