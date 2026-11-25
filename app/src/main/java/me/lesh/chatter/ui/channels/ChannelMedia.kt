package me.lesh.chatter.ui.channels

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import me.lesh.chatter.R
import me.lesh.chatter.channels.ChannelInfo
import me.lesh.chatter.chat.LinkText
import me.lesh.chatter.chat.PostSite
import me.lesh.chatter.chat.PostedIn
import me.lesh.chatter.chat.SharedItems
import me.lesh.chatter.chat.SharedLink
import me.lesh.chatter.chat.SharedMedia
import me.lesh.chatter.ui.chat.ImageOrigin
import me.lesh.chatter.ui.chat.LocalImageViewer
import me.lesh.chatter.ui.chat.LocalPosts

/** Twitch's purple, on the length of a clip or VOD as YouTube's red is on a YouTube video. */
private val TwitchPurple = Color(0xFF9146FF)
private val YouTubeRed = Color(0xFFCC0000)

/**
 * One picture, GIF or video as a square. Tapping opens it as the chat does: pictures and GIFs in
 * the image viewer, videos in the browser or their app. Holding goes to its message ([onHold]).
 * [channel] is set in a combined chat, whose items each show where they were posted.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MediaTile(
    media: SharedMedia,
    channel: ChannelInfo?,
    imageLoader: ImageLoader,
    onHold: (PostedIn) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewer = LocalImageViewer.current
    val uriHandler = LocalUriHandler.current
    val haptics = LocalHapticFeedback.current
    val origin = remember(media.url) { ImageOrigin() }
    DisposableEffect(origin) { onDispose { origin.coordinates = null } }
    val title = (media as? SharedMedia.Gif)?.title
    val description = title?.removeSurrounding("[", "]") ?: stringResource(if (media is SharedMedia.Video) R.string.channel_video else R.string.linked_image)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .combinedClickable(
                onClickLabel = description,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onHold(media.postedIn)
                },
                onClick = {
                    when (media) {
                        is SharedMedia.Video -> uriHandler.openUri(media.url)
                        else -> viewer?.show(media.url, title, origin) ?: uriHandler.openUri(media.url)
                    }
                },
            ),
    ) {
        when (media) {
            is SharedMedia.Image, is SharedMedia.Gif -> AsyncImage(
                model = media.url,
                contentDescription = description,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                onSuccess = { origin.image = it.result.image },
                modifier = Modifier
                    .fillMaxSize()
                    .onPlaced { origin.coordinates = it }
                    .graphicsLayer { alpha = if (viewer?.covers(origin) == true) 0f else 1f },
            )
            is SharedMedia.Video -> VideoStill(media, imageLoader)
        }
        if (media is SharedMedia.Gif) Tag(Modifier.align(Alignment.BottomStart)) { Text("GIF") }
        channel?.let {
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) { ChannelAvatar(it, imageLoader, 20.dp, showLive = false) }
        }
    }
}

/** A video's still and length, from the post the chat's card loads too; a play sign until it arrives. */
@Composable
private fun VideoStill(video: SharedMedia.Video, imageLoader: ImageLoader) {
    val repository = LocalPosts.current
    val post by produceState(repository?.known(video.link), video.link, repository) {
        if (repository != null && !repository.isFresh(video.link)) value = repository.post(video.link) ?: value
    }
    val still = post?.media?.pictureUrl
    Box(Modifier.fillMaxSize()) {
        if (still != null) {
            AsyncImage(still, post?.text, imageLoader, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        val color = if (video.link.site == PostSite.YouTube) YouTubeRed else TwitchPurple
        Tag(Modifier.align(Alignment.BottomStart), color) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(12.dp))
            post?.media?.lengthSeconds?.takeIf { it > 0 }?.let { Text(DateUtils.formatElapsedTime(it.toLong())) }
        }
    }
}

/** A small label on a tile, like a video's length. */
@Composable
private fun Tag(modifier: Modifier, color: Color = Color.Black.copy(alpha = 0.65f), content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides Color.White,
            LocalTextStyle provides MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        ) { content() }
    }
}

/**
 * A link with who posted it and when. Tapping opens it as the chat does, holding goes to its
 * message. [channel] as in [MediaTile].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LinkRow(link: SharedLink, channel: ChannelInfo?, imageLoader: ImageLoader, onHold: (PostedIn) -> Unit) {
    val uriHandler = LocalUriHandler.current
    val haptics = LocalHapticFeedback.current
    // Shown as the chat shows it: the real host, never what the sender dressed it up as.
    val shown = remember(link.url) { LinkText.display(link.url, short = true) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onHold(link.postedIn)
                },
                onClick = { uriHandler.openUri(link.url) },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Icon(painterResource(R.drawable.ic_link), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Text(shown, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                postedBy(link.postedIn),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        channel?.let { ChannelAvatar(it, imageLoader, 24.dp, showLive = false) }
    }
}

/** "Name · 4 min. ago", in the phone's language. */
@Composable
private fun postedBy(postedIn: PostedIn): String {
    val ago = DateUtils.getRelativeTimeSpanString(
        postedIn.timestamp,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
    val name = postedIn.displayName ?: postedIn.login
    return listOfNotNull(name, ago).joinToString(" · ")
}

/**
 * Every picture, GIF and video of a chat in one tab and every other link in the other, newest
 * first. [channelOf] names the channel of an item in a combined chat, null in a single channel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaAndLinksScreen(
    shared: SharedItems,
    startOnLinks: Boolean,
    channelOf: (PostedIn) -> ChannelInfo?,
    imageLoader: ImageLoader,
    onHold: (PostedIn) -> Unit,
    onBack: () -> Unit,
) {
    val pager = rememberPagerState(initialPage = if (startOnLinks) 1 else 0) { 2 }
    val scope = rememberCoroutineScope()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.channel_media_and_links)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = pager.currentPage, divider = {}) {
                Tab(
                    selected = pager.currentPage == 0,
                    onClick = { scope.launch { pager.animateScrollToPage(0) } },
                    text = { Text(stringResource(R.string.channel_media_count, shared.media.size)) },
                )
                Tab(
                    selected = pager.currentPage == 1,
                    onClick = { scope.launch { pager.animateScrollToPage(1) } },
                    text = { Text(stringResource(R.string.channel_links_count, shared.links.size)) },
                )
            }
            HorizontalPager(pager, Modifier.fillMaxSize(), verticalAlignment = Alignment.Top) { page ->
                if (page == 0) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(110.dp),
                        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        item(key = "hint", span = { GridItemSpan(maxLineSpan) }) {
                            PageNote(stringResource(if (shared.media.isEmpty()) R.string.channel_media_none else R.string.channel_shared_hint))
                        }
                        items(shared.media, key = { "m:" + it.url }) { media ->
                            MediaTile(media, channelOf(media.postedIn), imageLoader, onHold)
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        item(key = "hint") {
                            PageNote(stringResource(if (shared.links.isEmpty()) R.string.channel_links_none else R.string.channel_shared_hint))
                        }
                        items(shared.links, key = { "l:" + it.url }) { link ->
                            LinkRow(link, channelOf(link.postedIn), imageLoader, onHold)
                        }
                    }
                }
            }
        }
    }
}
