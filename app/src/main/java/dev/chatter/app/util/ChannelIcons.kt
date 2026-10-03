package dev.chatter.app.util

import android.content.Context
import androidx.core.graphics.drawable.IconCompat
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import dev.chatter.app.R
import dev.chatter.app.channels.ChannelRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * Twitch pictures as icons for notifications, shortcuts and bubbles. Each is downloaded once and
 * kept for the life of the process.
 */
class ChannelIcons(
    private val context: Context,
    private val channels: ChannelRepository,
    private val imageLoader: ImageLoader,
) {
    private val cache = ConcurrentHashMap<String, IconCompat>()

    /** The channel's avatar, or the app icon while it is not known yet. */
    suspend fun channel(login: String): IconCompat {
        cache[login]?.let { return it }
        val url = channels.info.value[login]?.avatarUrl ?: return fallback()
        return load(url)?.also { cache[login] = it } ?: fallback()
    }

    /**
     * Downloads a picture as an icon. Not an adaptive icon: Android crops those to their safe zone,
     * which cuts off the edges of an avatar.
     */
    suspend fun load(url: String): IconCompat? {
        // Hardware bitmaps cannot leave the process, and notification icons do.
        val request = ImageRequest.Builder(context).data(url).size(ICON_SIZE_PX).allowHardware(false).build()
        val image = (runCatching { imageLoader.execute(request) }.getOrNull() as? SuccessResult)?.image
        return (image as? BitmapImage)?.bitmap?.let { IconCompat.createWithBitmap(it) }
    }

    private fun fallback(): IconCompat = IconCompat.createWithResource(context, R.mipmap.ic_launcher)

    private companion object {
        /** Notification and shortcut icons are shown small; larger wastes memory. */
        const val ICON_SIZE_PX = 192
    }
}
