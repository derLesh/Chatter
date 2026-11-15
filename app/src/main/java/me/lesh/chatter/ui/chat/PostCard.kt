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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
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
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import me.lesh.chatter.chat.PostLabel
import me.lesh.chatter.chat.PostLink
import me.lesh.chatter.chat.PostMedia
import me.lesh.chatter.chat.PostRepository
import me.lesh.chatter.chat.PostSite
import me.lesh.chatter.settings.PostCardSettings
import me.lesh.chatter.settings.PostPictureSize
import me.lesh.chatter.settings.PostTextLines
import me.lesh.chatter.settings.Settings
import me.lesh.chatter.settings.postCard
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Where post cards get their posts. Without one, as in the settings preview, links stay links. */
val LocalPosts = staticCompositionLocalOf<PostRepository?> { null }

/** Which links become post cards, and how each site's cards look, from the settings. */
@Immutable
data class PostCardStyle(
    val sites: Set<PostSite> = emptySet(),
    val looks: Map<PostSite, PostCardLook> = emptyMap(),
) {
    fun look(site: PostSite): PostCardLook = looks[site] ?: PostCardLook()
}

/** Which parts of a post the cards of one site show, and how. */
@Immutable
data class PostCardLook(
    val avatar: Boolean = true,
    val date: Boolean = true,
    val media: Boolean = true,
    val counts: Boolean = true,
    /** 0 hides the text; [Int.MAX_VALUE] shows all of it. */
    val textLines: Int = PostTextLines.DEFAULT,
    val pictureHeight: Dp = pictureHeight(PostPictureSize.Medium),
    val thumbnailWidth: Dp = thumbnailWidth(PostPictureSize.Medium),
    val blurNsfw: Boolean = true,
    /** The picture on the left and the text beside it. */
    val compact: Boolean = false,
)

fun postCardStyle(settings: Settings) = PostCardStyle(
    sites = settings.postSites,
    looks = PostSite.entries.associateWith { postCardLook(settings.postCard(it), settings.blurNsfwPosts) },
)

private fun postCardLook(card: PostCardSettings, blurNsfw: Boolean) = PostCardLook(
    avatar = card.avatar,
    date = card.date,
    media = card.media,
    counts = card.counts,
    textLines = if (card.textLines >= PostTextLines.ALL) Int.MAX_VALUE else card.textLines,
    pictureHeight = pictureHeight(card.pictureSize),
    thumbnailWidth = thumbnailWidth(card.pictureSize),
    blurNsfw = blurNsfw,
    compact = card.compact,
)

fun pictureHeight(size: PostPictureSize): Dp = when (size) {
    PostPictureSize.Small -> 100.dp
    PostPictureSize.Medium -> 160.dp
    PostPictureSize.Large -> 260.dp
}

/** How wide the picture of a compact card is, next to its text. */
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
    PostSite.Twitch -> R.string.post_site_twitch
}

/** The site's logo, drawn on its cards. */
fun siteIcon(site: PostSite): Int = when (site) {
    PostSite.X -> R.drawable.ic_site_x
    PostSite.Bluesky -> R.drawable.ic_site_bluesky
    PostSite.YouTube -> R.drawable.ic_site_youtube
    PostSite.Twitch -> R.drawable.ic_site_twitch
}

/** Bluesky's butterfly, YouTube's button and Twitch's glitch are known by their color; X's logo is plain, so it takes [plain]. */
fun siteTint(site: PostSite, plain: Color): Color = when (site) {
    PostSite.X -> plain
    PostSite.Bluesky -> BLUESKY_BLUE
    PostSite.YouTube -> YOUTUBE_RED
    PostSite.Twitch -> TWITCH_PURPLE
}

private val BLUESKY_BLUE = Color(0xFF1185FE)
private val YOUTUBE_RED = Color(0xFFFF0033)
private val TWITCH_PURPLE = Color(0xFF9146FF)
/** The red Twitch marks a live stream with. */
private val LIVE_RED = Color(0xFFEB0400)

/** Draws a picture of the card; the settings preview draws its own instead of fetching. */
typealias PostPicture = @Composable (url: String, description: String?, modifier: Modifier) -> Unit

private val AVATAR_SIZE = 20.dp
private val COMPACT_AVATAR_SIZE = 16.dp
private val PROFILE_PICTURE_SIZE = 56.dp
private val LIVE_RING = 2.dp
/** As many as YouTube shows in its own lists; the card stays about as tall as its picture. */
private const val COMPACT_TEXT_LINES = 2
private const val COMPACT_PICTURE_ASPECT = 16f / 9f
/** Line height as a multiple of the text size, as tight as the chat's compact spacing. */
private const val COMPACT_LINE_HEIGHT = 1.2f
private const val PICTURE_MIN_ASPECT = 0.5f
private const val PICTURE_MAX_ASPECT = 4f
private const val PICTURE_DEFAULT_ASPECT = 16f / 9f
private val NSFW_BLUR = 24.dp
/** The same word in every language; "tap to show" does not fit on a small still. */
private const val NSFW_SHORT = "NSFW"
/** Twitch writes it in English in every language too. */
private const val LIVE = "LIVE"

/**
 * A post in place of its link. Tapping opens the post. Shows the link while the post loads, and for
 * good if it cannot be read.
 */
@Composable
internal fun PostCard(link: PostLink, card: PostCardStyle, style: ChatStyle, loader: ImageLoader) {
    val repository = LocalPosts.current
    // Starts from the cache, so a card scrolled back into view does not shrink to a link for a frame.
    // One due to be asked again stays on screen until the answer comes, and if none does.
    val fetched by produceState(repository?.known(link), link, repository) {
        if (repository != null && !repository.isFresh(link)) value = repository.post(link) ?: value
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
internal fun PostCardContent(post: Post, site: PostSite, cards: PostCardStyle, style: ChatStyle, open: () -> Unit, picture: PostPicture) {
    val card = cards.look(site)
    if (post.profile) {
        ProfileCardContent(post, site, card, style, open, picture)
        return
    }
    if (card.compact) {
        CompactCardContent(post, site, card, style, open, picture)
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
                withLabel(post, if (card.counts) counts(post) else ""),
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
 * A channel: its picture large on the left, and beside it the name, what it says about itself or
 * the stream's title, and its numbers. Live, the picture gets Twitch's red ring and mark.
 */
@Composable
private fun ProfileCardContent(post: Post, site: PostSite, card: PostCardLook, style: ChatStyle, open: () -> Unit, picture: PostPicture) {
    val secondarySize = (style.fontSize - 2).sp
    // The chat's line height would spread three short lines far apart beside the picture.
    val textLineHeight = (style.fontSize * COMPACT_LINE_HEIGHT).sp
    val secondaryLineHeight = ((style.fontSize - 2) * COMPACT_LINE_HEIGHT).sp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.cardFrame(MaterialTheme.colorScheme.outlineVariant, open).padding(10.dp),
    ) {
        post.avatarUrl?.takeIf { card.avatar }?.let { url ->
            Box(contentAlignment = Alignment.BottomCenter) {
                val ring = if (post.live) Modifier.border(LIVE_RING, LIVE_RED, CircleShape).padding(LIVE_RING * 2) else Modifier
                picture(url, null, ring.size(PROFILE_PICTURE_SIZE).clip(CircleShape))
                if (post.live) Overlay(LIVE, Modifier.offset(y = 4.dp), small = true, background = LIVE_RED)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = post.name,
                    fontSize = style.fontSize.sp,
                    lineHeight = textLineHeight,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (post.handle.isNotEmpty()) {
                    Text(
                        post.handle, color = style.secondaryText, fontSize = secondarySize, lineHeight = secondaryLineHeight,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (post.text.isNotEmpty() && card.textLines > 0) {
                Text(
                    text = post.text,
                    fontSize = style.fontSize.sp,
                    lineHeight = textLineHeight,
                    maxLines = if (card.compact) minOf(card.textLines, COMPACT_TEXT_LINES) else card.textLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (card.counts) {
                Text(
                    counts(post),
                    color = style.secondaryText,
                    fontSize = secondarySize,
                    lineHeight = secondaryLineHeight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // In the bottom corner like on every other card, not centred with the text.
        SiteLogo(site, style, Modifier.align(Alignment.Bottom))
    }
}

/**
 * The picture on the left and the rest beside it, so the card is about as tall as the picture. The
 * text comes first, since a video's title or a post's words say more than who made them.
 */
@Composable
private fun CompactCardContent(post: Post, site: PostSite, card: PostCardLook, style: ChatStyle, open: () -> Unit, picture: PostPicture) {
    val secondarySize = (style.fontSize - 2).sp
    // The chat's line height would make two lines of text taller than the picture beside them.
    val textLineHeight = (style.fontSize * COMPACT_LINE_HEIGHT).sp
    val secondaryLineHeight = ((style.fontSize - 2) * COMPACT_LINE_HEIGHT).sp
    val video = site.videos
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.cardFrame(MaterialTheme.colorScheme.outlineVariant, open).padding(8.dp),
    ) {
        val media = post.media?.takeIf { card.media }
        media?.let {
            PostMediaBox(
                it, post.nsfw && card.blurNsfw, open, picture,
                Modifier.width(card.thumbnailWidth).aspectRatio(COMPACT_PICTURE_ASPECT), compact = true,
            )
        }
        val text = post.text.isNotEmpty() && card.textLines > 0
        // At least as tall as the picture, so the text starts at its top and the author ends at its bottom.
        Column(
            verticalArrangement = if (text) Arrangement.SpaceBetween else Arrangement.Bottom,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = if (media != null) card.thumbnailWidth / COMPACT_PICTURE_ASPECT else 0.dp),
        ) {
            if (text) {
                Text(
                    text = post.text,
                    fontSize = style.fontSize.sp,
                    fontWeight = if (video) FontWeight.Medium else null,
                    lineHeight = textLineHeight,
                    maxLines = minOf(card.textLines, COMPACT_TEXT_LINES),
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // A video's date goes with its views, as YouTube writes it; a post's goes with its author.
                AuthorLine(post, card, style, picture, secondarySize, secondaryLineHeight, withDate = !video)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        if (video) ViewsLine(post, card, style, secondarySize, secondaryLineHeight)
                        else if (card.counts) CountIcons(post, style, secondarySize, secondaryLineHeight)
                    }
                    SiteLogo(site, style)
                }
            }
        }
    }
}

@Composable
private fun AuthorLine(
    post: Post,
    card: PostCardLook,
    style: ChatStyle,
    picture: PostPicture,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    withDate: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        post.avatarUrl?.takeIf { card.avatar }?.let { picture(it, null, Modifier.size(COMPACT_AVATAR_SIZE).clip(CircleShape)) }
        val posted = rememberPostedDate(post)?.takeIf { card.date && withDate }
        Text(
            text = if (posted != null) "${post.name} · $posted" else post.name,
            color = style.secondaryText,
            fontSize = fontSize,
            lineHeight = lineHeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ViewsLine(post: Post, card: PostCardLook, style: ChatStyle, fontSize: TextUnit, lineHeight: TextUnit) {
    val counts = if (card.counts) counts(post) else ""
    val posted = rememberPostedDate(post)?.takeIf { card.date }
    Text(
        text = withLabel(post, listOfNotNull(counts.ifEmpty { null }, posted).joinToString(" · ")),
        color = style.secondaryText,
        fontSize = fontSize,
        lineHeight = lineHeight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Replies, reposts and likes as an icon and a number each; written out they do not fit beside a
 * picture. What nobody did is left out.
 */
@Composable
private fun CountIcons(post: Post, style: ChatStyle, fontSize: TextUnit, lineHeight: TextUnit) {
    val compact = remember { CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT) }
    val iconSize = (style.fontSize - 2).dp
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            Triple(painterResource(R.drawable.ic_reply), R.plurals.post_replies, post.replies),
            Triple(painterResource(R.drawable.ic_repost), R.plurals.post_reposts, post.reposts),
            Triple(rememberVectorPainter(Icons.Default.FavoriteBorder), R.plurals.post_likes, post.likes),
        ).filter { it.third > 0 }.forEach { (icon, label, value) ->
            val number = compact.format(value)
            val description = pluralStringResource(label, value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), number)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.clearAndSetSemantics { contentDescription = description },
            ) {
                Icon(icon, contentDescription = null, tint = style.secondaryText, modifier = Modifier.size(iconSize))
                Text(number, color = style.secondaryText, fontSize = fontSize, lineHeight = lineHeight, maxLines = 1)
            }
        }
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
private fun SiteLogo(site: PostSite, style: ChatStyle, modifier: Modifier = Modifier) {
    Icon(
        painterResource(siteIcon(site)),
        contentDescription = stringResource(siteName(site)),
        tint = siteTint(site, style.secondaryText),
        modifier = modifier.size(style.fontSize.dp),
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
            media.video && compact && media.lengthSeconds > 0 -> Unit
            media.video && compact -> Icon(
                Icons.Default.PlayArrow,
                contentDescription = stringResource(R.string.post_video),
                tint = Color.White,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(3.dp).size(16.dp),
            )
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
private fun Overlay(text: String, modifier: Modifier = Modifier, small: Boolean = false, background: Color = Color.Black.copy(alpha = 0.6f)) {
    Text(
        text = text,
        color = Color.White,
        fontSize = if (small) 11.sp else 13.sp,
        lineHeight = if (small) 13.sp else TextUnit.Unspecified,
        modifier = modifier
            .background(background, RoundedCornerShape(if (small) 4.dp else 6.dp))
            .padding(horizontal = if (small) 4.dp else 8.dp, vertical = if (small) 1.dp else 4.dp),
    )
}

/** "Clip · [rest]", so a clip and a VOD can be told apart without their picture. */
@Composable
private fun withLabel(post: Post, rest: String): String {
    val label = post.label?.let { stringResource(labelName(it)) } ?: return rest
    return if (rest.isEmpty()) label else "$label · $rest"
}

private fun labelName(label: PostLabel): Int = when (label) {
    PostLabel.Clip -> R.string.post_label_clip
    PostLabel.Vod -> R.string.post_label_vod
    PostLabel.Highlight -> R.string.post_label_highlight
    PostLabel.Upload -> R.string.post_label_upload
}

/** "3:33" or "1:02:03", as YouTube writes it. */
internal fun videoLength(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = seconds / 60 % 60
    val rest = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, rest) else "%d:%02d".format(Locale.ROOT, minutes, rest)
}

/**
 * "12K replies · 3K reposts · 40K likes · 2M views", leaving out what nobody did. A channel has
 * "12K viewers · 1.2M followers · Just Chatting" instead.
 */
@Composable
private fun counts(post: Post): String {
    val compact = remember { CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT) }
    return listOfNotNull(
        count(R.plurals.post_replies, post.replies, compact),
        count(R.plurals.post_reposts, post.reposts, compact),
        count(R.plurals.post_likes, post.likes, compact),
        count(R.plurals.post_viewers, post.viewers, compact),
        count(R.plurals.post_followers, post.followers, compact),
        count(R.plurals.post_views, post.views, compact),
        post.category.ifEmpty { null },
    ).joinToString(" · ")
}

@Composable
private fun count(res: Int, value: Long, compact: CompactDecimalFormat): String? =
    if (value <= 0) null
    else pluralStringResource(res, value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), compact.format(value))
