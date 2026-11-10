package me.lesh.chatter.ui.chat

import android.icu.text.CompactDecimalFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import me.lesh.chatter.R
import me.lesh.chatter.chat.Post
import me.lesh.chatter.chat.PostLink
import me.lesh.chatter.chat.PostMedia
import me.lesh.chatter.chat.PostRepository
import me.lesh.chatter.chat.PostSite
import me.lesh.chatter.settings.PostPictureSize
import me.lesh.chatter.settings.PostTextLines
import me.lesh.chatter.settings.Settings
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Where post cards get their posts. Without one, as in the settings preview, links stay links. */
val LocalPosts = staticCompositionLocalOf<PostRepository?> { null }

/** Which links become post cards and which parts of a post they show, from the settings. */
@Immutable
data class PostCardStyle(
    val sites: Set<PostSite> = emptySet(),
    val avatar: Boolean = true,
    val date: Boolean = true,
    val media: Boolean = true,
    val counts: Boolean = true,
    /** 0 hides the text; [Int.MAX_VALUE] shows all of it. */
    val textLines: Int = PostTextLines.DEFAULT,
    val pictureHeight: Dp = pictureHeight(PostPictureSize.Medium),
    val thumbnailWidth: Dp = thumbnailWidth(PostPictureSize.Medium),
    val blurNsfw: Boolean = true,
)

fun postCardStyle(settings: Settings) = PostCardStyle(
    sites = settings.postSites,
    avatar = settings.postAvatar,
    date = settings.postDate,
    media = settings.postMedia,
    counts = settings.postCounts,
    textLines = if (settings.postTextLines >= PostTextLines.ALL) Int.MAX_VALUE else settings.postTextLines,
    pictureHeight = pictureHeight(settings.postPictureSize),
    thumbnailWidth = thumbnailWidth(settings.postPictureSize),
    blurNsfw = settings.blurNsfwPosts,
)

fun pictureHeight(size: PostPictureSize): Dp = when (size) {
    PostPictureSize.Small -> 100.dp
    PostPictureSize.Medium -> 160.dp
    PostPictureSize.Large -> 260.dp
}

/** How wide a video's still is, next to its title. */
fun thumbnailWidth(size: PostPictureSize): Dp = when (size) {
    PostPictureSize.Small -> 96.dp
    PostPictureSize.Medium -> 128.dp
    PostPictureSize.Large -> 168.dp
}

/** How a site is named in the settings, and to a screen reader on its cards. */
fun siteName(site: PostSite): Int = when (site) {
    PostSite.X -> R.string.post_site_x
    PostSite.Bluesky -> R.string.post_site_bluesky
    PostSite.YouTube -> R.string.post_site_youtube
}

/** The site's logo, drawn on its cards. */
fun siteIcon(site: PostSite): Int = when (site) {
    PostSite.X -> R.drawable.ic_site_x
    PostSite.Bluesky -> R.drawable.ic_site_bluesky
    PostSite.YouTube -> R.drawable.ic_site_youtube
}

/** Bluesky's butterfly and YouTube's button are known by their color; X's logo is plain, so it takes the card's text color. */
private fun siteTint(site: PostSite, style: ChatStyle): Color = when (site) {
    PostSite.X -> style.secondaryText
    PostSite.Bluesky -> BLUESKY_BLUE
    PostSite.YouTube -> YOUTUBE_RED
}

private val BLUESKY_BLUE = Color(0xFF1185FE)
private val YOUTUBE_RED = Color(0xFFFF0033)

/** Draws a picture of the card; the settings preview draws its own instead of fetching. */
typealias PostPicture = @Composable (url: String, description: String?, modifier: Modifier) -> Unit

private val AVATAR_SIZE = 20.dp
private val CHANNEL_AVATAR_SIZE = 16.dp
/** As many as YouTube shows in its own lists; the card stays about as tall as its still. */
private const val VIDEO_TITLE_LINES = 2
private const val VIDEO_ASPECT = 16f / 9f
/** Line height as a multiple of the text size, as tight as the chat's compact spacing. */
private const val VIDEO_LINE_HEIGHT = 1.2f
private const val PICTURE_MIN_ASPECT = 0.5f
private const val PICTURE_MAX_ASPECT = 4f
private const val PICTURE_DEFAULT_ASPECT = 16f / 9f
private val NSFW_BLUR = 24.dp
/** The same word in every language; "tap to show" does not fit on a small still. */
private const val NSFW_SHORT = "NSFW"

/**
 * A post in place of its link. Tapping opens the post. Shows the link while the post loads, and for
 * good if it cannot be read.
 */
@Composable
internal fun PostCard(link: PostLink, card: PostCardStyle, style: ChatStyle, loader: ImageLoader) {
    val repository = LocalPosts.current
    // Starts from the cache, so a card scrolled back into view does not shrink to a link for a frame.
    val fetched by produceState(repository?.known(link), link, repository) {
        if (value == null) value = repository?.post(link)
    }
    val post = fetched
    if (post == null) {
        Text(
            text = remember(link.url, style.linkColor) { linkText(link.url, style) },
            fontSize = style.fontSize.sp,
            lineHeight = style.lineHeight,
        )
        return
    }
    val uriHandler = LocalUriHandler.current
    PostCardContent(post, link.site, card, style, open = { uriHandler.openUri(link.url) }) { url, description, modifier ->
        AsyncImage(url, description, loader, modifier, contentScale = ContentScale.Crop)
    }
}

/** The card for [post] from [site], with the parts [card] asks for. */
@Composable
internal fun PostCardContent(post: Post, site: PostSite, card: PostCardStyle, style: ChatStyle, open: () -> Unit, picture: PostPicture) {
    if (site == PostSite.YouTube) {
        VideoCardContent(post, site, card, style, open, picture)
        return
    }
    val secondarySize = (style.fontSize - 2).sp
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.cardFrame(MaterialTheme.colorScheme.outlineVariant, open).padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            post.avatarUrl?.takeIf { card.avatar }?.let { picture(it, null, Modifier.size(AVATAR_SIZE).clip(CircleShape)) }
            Text(
                text = post.name,
                fontSize = style.fontSize.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            val posted = rememberPostedDate(post)
            val byline = listOfNotNull(post.handle.takeIf { it.isNotEmpty() }?.let { "@$it" }, posted?.takeIf { card.date })
            Text(
                text = byline.joinToString(" · "),
                color = style.secondaryText,
                fontSize = secondarySize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (post.text.isNotEmpty() && card.textLines > 0) {
            Text(
                text = post.text,
                fontSize = style.fontSize.sp,
                lineHeight = style.lineHeight,
                maxLines = card.textLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        post.media?.takeIf { card.media }?.let { media ->
            val aspect = (media.aspectRatio ?: PICTURE_DEFAULT_ASPECT).coerceIn(PICTURE_MIN_ASPECT, PICTURE_MAX_ASPECT)
            PostMediaBox(media, post.nsfw && card.blurNsfw, open, picture, Modifier.fillMaxWidth().heightByAspect(aspect, card.pictureHeight))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (card.counts) counts(post) else "",
                color = style.secondaryText,
                fontSize = secondarySize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            SiteLogo(site, style)
        }
    }
}

/**
 * A video: its still on the left and what it is beside it, so the card is about as tall as the
 * still. The title comes first, since it says more than the channel.
 */
@Composable
private fun VideoCardContent(post: Post, site: PostSite, card: PostCardStyle, style: ChatStyle, open: () -> Unit, picture: PostPicture) {
    val secondarySize = (style.fontSize - 2).sp
    // The chat's line height would make two title lines taller than the still beside them.
    val titleLineHeight = (style.fontSize * VIDEO_LINE_HEIGHT).sp
    val secondaryLineHeight = ((style.fontSize - 2) * VIDEO_LINE_HEIGHT).sp
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.cardFrame(MaterialTheme.colorScheme.outlineVariant, open).padding(8.dp),
    ) {
        val media = post.media?.takeIf { card.media }
        media?.let {
            PostMediaBox(
                it, post.nsfw && card.blurNsfw, open, picture,
                Modifier.width(card.thumbnailWidth).aspectRatio(VIDEO_ASPECT), compact = true,
            )
        }
        val title = post.text.isNotEmpty() && card.textLines > 0
        // At least as tall as the still, so the title starts at its top and the channel ends at its bottom.
        Column(
            verticalArrangement = if (title) Arrangement.SpaceBetween else Arrangement.Bottom,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = if (media != null) card.thumbnailWidth / VIDEO_ASPECT else 0.dp),
        ) {
            if (title) {
                Text(
                    text = post.text,
                    fontSize = style.fontSize.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = titleLineHeight,
                    maxLines = minOf(card.textLines, VIDEO_TITLE_LINES),
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ChannelLine(post, card, style, picture, secondarySize, secondaryLineHeight)
                DetailsLine(post, site, card, style, secondarySize, secondaryLineHeight)
            }
        }
    }
}

@Composable
private fun ChannelLine(post: Post, card: PostCardStyle, style: ChatStyle, picture: PostPicture, fontSize: TextUnit, lineHeight: TextUnit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        post.avatarUrl?.takeIf { card.avatar }?.let { picture(it, null, Modifier.size(CHANNEL_AVATAR_SIZE).clip(CircleShape)) }
        Text(
            text = post.name,
            color = style.secondaryText,
            fontSize = fontSize,
            lineHeight = lineHeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Views and date, with the site's logo at the end. */
@Composable
private fun DetailsLine(post: Post, site: PostSite, card: PostCardStyle, style: ChatStyle, fontSize: TextUnit, lineHeight: TextUnit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val counts = if (card.counts) counts(post) else ""
        val posted = rememberPostedDate(post)?.takeIf { card.date }
        Text(
            text = listOfNotNull(counts.ifEmpty { null }, posted).joinToString(" · "),
            color = style.secondaryText,
            fontSize = fontSize,
            lineHeight = lineHeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        SiteLogo(site, style)
    }
}

/** The outline every card has, tappable as a whole. */
private fun Modifier.cardFrame(outline: Color, open: () -> Unit): Modifier {
    val shape = RoundedCornerShape(12.dp)
    return padding(top = 4.dp, bottom = 2.dp)
        .fillMaxWidth()
        .clip(shape)
        .border(1.dp, outline, shape)
        .clickable(onClick = open)
}

/** Always shown: with the link taken out of the line, nothing else says where the post is. */
@Composable
private fun SiteLogo(site: PostSite, style: ChatStyle) {
    Icon(
        painterResource(siteIcon(site)),
        contentDescription = stringResource(siteName(site)),
        tint = siteTint(site, style),
        modifier = Modifier.size(style.fontSize.dp),
    )
}

@Composable
private fun rememberPostedDate(post: Post): String? = remember(post.postedAt) {
    post.postedAt.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
}

/**
 * The post's first picture, or a still of its video, in the room [modifier] gives it. An NSFW one
 * stays blurred until the first tap; after that a tap opens the post. [compact] fits a small still.
 */
@Composable
private fun PostMediaBox(
    media: PostMedia,
    blurred: Boolean,
    open: () -> Unit,
    picture: PostPicture,
    modifier: Modifier,
    compact: Boolean = false,
) {
    var hidden by remember(media.pictureUrl, blurred) { mutableStateOf(blurred) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { if (hidden) hidden = false else open() },
    ) {
        picture(
            media.pictureUrl,
            stringResource(R.string.post_picture),
            Modifier.matchParentSize().then(if (hidden) Modifier.blur(NSFW_BLUR) else Modifier),
        )
        when {
            hidden -> Overlay(if (compact) NSFW_SHORT else stringResource(R.string.post_nsfw))
            // The length in the corner already says it is a video.
            media.video && compact -> Unit
            media.video -> Icon(
                Icons.Default.PlayArrow,
                contentDescription = stringResource(R.string.post_video),
                tint = Color.White,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(6.dp).size(28.dp),
            )
        }
        val corner = when {
            media.more > 0 -> "+${media.more}"
            media.lengthSeconds > 0 -> remember(media.lengthSeconds) { videoLength(media.lengthSeconds) }
            else -> null
        }
        corner?.let { Overlay(it, Modifier.align(Alignment.BottomEnd).padding(if (compact) 4.dp else 6.dp), small = compact) }
    }
}

/**
 * The width divided by [aspect], but at most [maxHeight]. Worked out while measuring, since
 * BoxWithConstraints would compose every picture in the chat list a second time.
 */
private fun Modifier.heightByAspect(aspect: Float, maxHeight: Dp) = layout { measurable, constraints ->
    val limit = maxHeight.toPx()
    val wanted = if (constraints.hasBoundedWidth) minOf(constraints.maxWidth / aspect, limit) else limit
    val height = wanted.roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
    val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
    layout(placeable.width, height) { placeable.place(0, 0) }
}

@Composable
private fun Overlay(text: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Text(
        text = text,
        color = Color.White,
        fontSize = if (small) 11.sp else 13.sp,
        lineHeight = if (small) 13.sp else TextUnit.Unspecified,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(if (small) 4.dp else 6.dp))
            .padding(horizontal = if (small) 4.dp else 8.dp, vertical = if (small) 1.dp else 4.dp),
    )
}

/** "3:33" or "1:02:03", as YouTube writes it. */
internal fun videoLength(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = seconds / 60 % 60
    val rest = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, rest) else "%d:%02d".format(Locale.ROOT, minutes, rest)
}

/** "12K replies · 3K reposts · 40K likes · 2M views", leaving out what nobody did. */
@Composable
private fun counts(post: Post): String {
    val compact = remember { CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT) }
    return listOfNotNull(
        count(R.plurals.post_replies, post.replies, compact),
        count(R.plurals.post_reposts, post.reposts, compact),
        count(R.plurals.post_likes, post.likes, compact),
        count(R.plurals.post_views, post.views, compact),
    ).joinToString(" · ")
}

@Composable
private fun count(res: Int, value: Long, compact: CompactDecimalFormat): String? =
    if (value <= 0) null
    else pluralStringResource(res, value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), compact.format(value))
