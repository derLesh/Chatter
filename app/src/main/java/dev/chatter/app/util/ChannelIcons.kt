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
 * Twitch pictures as the small icons Android asks for — on a notification, on a shortcut, on a
 * bubble. Notifications and shortcuts want the same handful of channel avatars over and over, so
 * each one is downloaded once and then kept for as long as the process lives.
 */
class ChannelIcons(
    private val context: Context,
    private val channels: ChannelRepository,
    private val imageLoader: ImageLoader,
) {
    private val cache = ConcurrentHashMap<String, IconCompat>()

    /** The channel's avatar, or the app icon while Twitch has not told us about one yet. */
    suspend fun channel(login: String): IconCompat {
        cache[login]?.let { return it }
        val url = channels.info.value[login]?.avatarUrl ?: return fallback()
        return load(url)?.also { cache[login] = it } ?: fallback()
    }

    /**
     * Downloads a picture as an icon. Deliberately not an adaptive icon: Android crops those to
     * their inner safe zone, which blows an avatar up and cuts its edges off.
     */
    suspend fun load(url: String): IconCompat? {
        // Hardware bitmaps cannot leave the process, and a notification icon does exactly that.
        val request = ImageRequest.Builder(context).data(url).size(ICON_SIZE_PX).allowHardware(false).build()
        val image = (runCatching { imageLoader.execute(request) }.getOrNull() as? SuccessResult)?.image
        return (image as? BitmapImage)?.bitmap?.let { IconCompat.createWithBitmap(it) }
    }

    private fun fallback(): IconCompat = IconCompat.createWithResource(context, R.mipmap.ic_launcher)

    private companion object {
        /** Android shows notification and shortcut icons small; anything larger is wasted memory. */
        const val ICON_SIZE_PX = 192
    }
}
