package dev.chatter.app.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import dev.chatter.app.channels.ChannelInfo
import dev.chatter.app.ui.theme.LiveRed

/** Round avatar with a red ring and dot while the channel is live. */
@Composable
fun ChannelAvatar(info: ChannelInfo?, imageLoader: ImageLoader, size: Dp) {
    Box(Modifier.size(size)) {
        AsyncImage(
            model = info?.avatarUrl,
            contentDescription = null,
            imageLoader = imageLoader,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                // The red ring means "live" wherever an avatar appears.
                .then(if (info?.isLive == true) Modifier.border(2.dp, LiveRed, CircleShape) else Modifier),
        )
    }
}

/**
 * Avatars of a combined chat's first two channels, overlapping. More would not be recognizable at
 * title bar size.
 */
@Composable
fun GroupAvatar(channels: List<String>, info: Map<String, ChannelInfo>, imageLoader: ImageLoader, size: Dp) {
    val part = size * 0.68f
    Box(Modifier.size(size)) {
        channels.getOrNull(0)?.let { first ->
            Box(Modifier.align(Alignment.TopStart)) { ChannelAvatar(info[first], imageLoader, part) }
        }
        channels.getOrNull(1)?.let { second ->
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    // A ring in the background color separates it from the avatar below.
                    .border(2.dp, MaterialTheme.colorScheme.surfaceContainer, CircleShape),
            ) { ChannelAvatar(info[second], imageLoader, part) }
        }
    }
}
