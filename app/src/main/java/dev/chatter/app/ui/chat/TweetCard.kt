package dev.chatter.app.ui.chat

import android.icu.text.CompactDecimalFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import dev.chatter.app.R
import dev.chatter.app.chat.Tweet
import dev.chatter.app.chat.TweetLink
import dev.chatter.app.chat.TweetMedia
import dev.chatter.app.chat.TweetRepository
import dev.chatter.app.settings.Settings
import dev.chatter.app.settings.TweetPictureSize
import dev.chatter.app.settings.TweetTextLines
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Where post cards get their posts. Without one, as in the settings preview, links stay links. */
val LocalTweets = staticCompositionLocalOf<TweetRepository?> { null }

/** Which parts of a post its card shows, from the settings. */
@Immutable
data class TweetCardStyle(
    val avatar: Boolean = true,
    val date: Boolean = true,
    val media: Boolean = true,
    val counts: Boolean = true,
    /** 0 hides the text; [Int.MAX_VALUE] shows all of it. */
    val textLines: Int = TweetTextLines.DEFAULT,
    val pictureHeight: Dp = pictureHeight(TweetPictureSize.Medium),
    val blurSensitive: Boolean = true,
)

fun tweetCardStyle(settings: Settings) = TweetCardStyle(
    avatar = settings.tweetAvatar,
    date = settings.tweetDate,
    media = settings.tweetMedia,
    counts = settings.tweetCounts,
    textLines = if (settings.tweetTextLines >= TweetTextLines.ALL) Int.MAX_VALUE else settings.tweetTextLines,
    pictureHeight = pictureHeight(settings.tweetPictureSize),
    blurSensitive = settings.blurSensitiveTweets,
)

fun pictureHeight(size: TweetPictureSize): Dp = when (size) {
    TweetPictureSize.Small -> 100.dp
    TweetPictureSize.Medium -> 160.dp
    TweetPictureSize.Large -> 260.dp
}

/** Draws a picture of the card; the settings preview draws its own instead of fetching. */
typealias TweetPicture = @Composable (url: String, description: String?, modifier: Modifier) -> Unit

private val AVATAR_SIZE = 20.dp
private const val PICTURE_MIN_ASPECT = 0.5f
private const val PICTURE_MAX_ASPECT = 4f
private const val PICTURE_DEFAULT_ASPECT = 16f / 9f
private val SENSITIVE_BLUR = 24.dp

/**
 * A post on X in place of its link. Tapping opens the post. Shows the link while the post loads,
 * and for good if it cannot be read.
 */
@Composable
internal fun TweetCard(link: TweetLink, card: TweetCardStyle, style: ChatStyle, loader: ImageLoader) {
    val repository = LocalTweets.current
    // Starts from the cache, so a card scrolled back into view does not shrink to a link for a frame.
    val tweet by produceState(repository?.known(link.id), link.id, repository) {
        if (value == null) value = repository?.tweet(link.id)
    }
    val post = tweet
    if (post == null) {
        Text(
            text = remember(link.url, style.linkColor) { linkText(link.url, style) },
            fontSize = style.fontSize.sp,
            lineHeight = (style.fontSize * 1.45f).sp,
        )
        return
    }
    val uriHandler = LocalUriHandler.current
    TweetCardContent(post, card, style, open = { uriHandler.openUri(link.url) }) { url, description, modifier ->
        AsyncImage(url, description, loader, modifier, contentScale = ContentScale.Crop)
    }
}

/** The card for [post], with the parts [card] asks for. */
@Composable
internal fun TweetCardContent(post: Tweet, card: TweetCardStyle, style: ChatStyle, open: () -> Unit, picture: TweetPicture) {
    val shape = RoundedCornerShape(12.dp)
    val secondarySize = (style.fontSize - 2).sp
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .padding(top = 4.dp, bottom = 2.dp)
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = open)
            .padding(10.dp),
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
            val posted = remember(post.postedAt) { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(post.postedAt)) }
            Text(
                text = if (card.date) "@${post.handle} · $posted" else "@${post.handle}",
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
                lineHeight = (style.fontSize * 1.45f).sp,
                maxLines = card.textLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        post.media?.takeIf { card.media }?.let { media ->
            TweetMediaBox(media, post.sensitive && card.blurSensitive, card.pictureHeight, open, picture)
        }
        if (card.counts) {
            counts(post).takeIf { it.isNotEmpty() }?.let {
                Text(it, color = style.secondaryText, fontSize = secondarySize, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * The post's first picture, or a still of its video, across the card and at most [maxHeight] tall.
 * A sensitive one stays blurred until the first tap; after that a tap opens the post.
 */
@Composable
private fun TweetMediaBox(media: TweetMedia, blurred: Boolean, maxHeight: Dp, open: () -> Unit, picture: TweetPicture) {
    var hidden by remember(media.pictureUrl, blurred) { mutableStateOf(blurred) }
    val aspect = (media.aspectRatio ?: PICTURE_DEFAULT_ASPECT).coerceIn(PICTURE_MIN_ASPECT, PICTURE_MAX_ASPECT)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(min(maxWidth / aspect, maxHeight))
                .clip(RoundedCornerShape(8.dp))
                .clickable { if (hidden) hidden = false else open() },
        ) {
            picture(
                media.pictureUrl,
                stringResource(R.string.tweet_picture),
                Modifier.matchParentSize().then(if (hidden) Modifier.blur(SENSITIVE_BLUR) else Modifier),
            )
            when {
                hidden -> Overlay(stringResource(R.string.tweet_sensitive))
                media.video -> Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.tweet_video),
                    tint = Color.White,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(6.dp).size(28.dp),
                )
            }
            if (media.more > 0) {
                Overlay("+${media.more}", Modifier.align(Alignment.BottomEnd).padding(6.dp))
            }
        }
    }
}

@Composable
private fun Overlay(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 13.sp,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** "12K replies · 3K reposts · 40K likes", leaving out what nobody did. */
@Composable
private fun counts(post: Tweet): String {
    val compact = remember { CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT) }
    return listOfNotNull(
        count(R.plurals.tweet_replies, post.replies, compact),
        count(R.plurals.tweet_reposts, post.reposts, compact),
        count(R.plurals.tweet_likes, post.likes, compact),
    ).joinToString(" · ")
}

@Composable
private fun count(res: Int, value: Long, compact: CompactDecimalFormat): String? =
    if (value <= 0) null
    else pluralStringResource(res, value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), compact.format(value))
