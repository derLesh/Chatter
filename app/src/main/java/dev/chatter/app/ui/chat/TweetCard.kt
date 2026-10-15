package dev.chatter.app.ui.chat

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import dev.chatter.app.R
import dev.chatter.app.chat.Tweet
import dev.chatter.app.chat.TweetLink
import dev.chatter.app.chat.TweetMedia
import dev.chatter.app.chat.TweetRepository
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Where post cards get their posts. Without one, as in the settings preview, links stay links. */
val LocalTweets = staticCompositionLocalOf<TweetRepository?> { null }

private val CARD_MAX_WIDTH = 340.dp
private val AVATAR_SIZE = 20.dp
private const val TEXT_MAX_LINES = 6
/** Tall pictures are cut to this so one post does not fill the screen. */
private const val PICTURE_MIN_ASPECT = 1f
private const val PICTURE_MAX_ASPECT = 2.5f
private const val PICTURE_DEFAULT_ASPECT = 16f / 9f
private val SENSITIVE_BLUR = 24.dp

/**
 * A post on X in place of its link: author, text, picture and counts. Tapping opens the post. Shows
 * the link while the post loads, and for good if it cannot be read.
 */
@Composable
internal fun TweetCard(link: TweetLink, style: ChatStyle, loader: ImageLoader) {
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
    val shape = RoundedCornerShape(12.dp)
    val secondarySize = (style.fontSize - 2).sp
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .padding(top = 4.dp, bottom = 2.dp)
            .widthIn(max = CARD_MAX_WIDTH)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable { uriHandler.openUri(link.url) }
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            post.avatarUrl?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    imageLoader = loader,
                    modifier = Modifier.size(AVATAR_SIZE).clip(CircleShape),
                )
            }
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
                text = "@${post.handle} · $posted",
                color = style.secondaryText,
                fontSize = secondarySize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (post.text.isNotEmpty()) {
            Text(
                text = post.text,
                fontSize = style.fontSize.sp,
                lineHeight = (style.fontSize * 1.45f).sp,
                maxLines = TEXT_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
        post.media?.let { media ->
            TweetPicture(media, post.sensitive && style.blurSensitive, loader) { uriHandler.openUri(link.url) }
        }
        counts(post).takeIf { it.isNotEmpty() }?.let {
            Text(it, color = style.secondaryText, fontSize = secondarySize, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The post's first picture, or a still of its video. A sensitive one stays blurred until the first
 * tap; after that a tap opens the post.
 */
@Composable
private fun TweetPicture(media: TweetMedia, blurred: Boolean, loader: ImageLoader, open: () -> Unit) {
    var hidden by remember(media.pictureUrl, blurred) { mutableStateOf(blurred) }
    val aspect = (media.aspectRatio ?: PICTURE_DEFAULT_ASPECT).coerceIn(PICTURE_MIN_ASPECT, PICTURE_MAX_ASPECT)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(8.dp))
            .clickable { if (hidden) hidden = false else open() },
    ) {
        AsyncImage(
            model = media.pictureUrl,
            contentDescription = stringResource(R.string.tweet_picture),
            imageLoader = loader,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize().then(if (hidden) Modifier.blur(SENSITIVE_BLUR) else Modifier),
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
