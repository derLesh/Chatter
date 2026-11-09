package me.lesh.chatter.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import me.lesh.chatter.R
import me.lesh.chatter.channels.ChannelRepository
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
     * Downloads a picture as an adaptive icon, so every launcher, share menu and notification gives
     * it the shape it gives app icons there: round on Pixel, a squircle on One UI.
     */
    suspend fun load(url: String): IconCompat? {
        // Hardware bitmaps cannot leave the process, and notification icons do.
        val request = ImageRequest.Builder(context).data(url).size(AVATAR_SIZE_PX).allowHardware(false).build()
        val image = (runCatchingCancellable { imageLoader.execute(request) }.getOrNull() as? SuccessResult)?.image
        return (image as? BitmapImage)?.bitmap?.let { IconCompat.createWithAdaptiveBitmap(fitIntoShape(it)) }
    }

    /**
     * Shrinks the avatar into the middle of an adaptive icon. Android shows only that middle and
     * cuts the shape out of it; an avatar filling the whole icon would lose its edges.
     */
    private fun fitIntoShape(avatar: Bitmap): Bitmap {
        val icon = createBitmap(ICON_SIZE_PX, ICON_SIZE_PX)
        val inset = (ICON_SIZE_PX - AVATAR_SIZE_PX) / 2f
        val bounds = RectF(inset, inset, ICON_SIZE_PX - inset, ICON_SIZE_PX - inset)
        Canvas(icon).drawBitmap(avatar, null, bounds, Paint(Paint.FILTER_BITMAP_FLAG))
        return icon
    }

    // One object, so a notification can tell that the picture did not change.
    private val fallback by lazy { IconCompat.createWithResource(context, R.mipmap.ic_launcher) }

    private fun fallback(): IconCompat = fallback

    private companion object {
        /** Notification and shortcut icons are shown small; larger wastes memory. */
        const val ICON_SIZE_PX = 216

        // Android shows the middle 2/3 of an adaptive icon. A little more, so no shape meets the
        // empty border.
        const val AVATAR_SIZE_PX = 150
    }
}
