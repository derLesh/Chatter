package dev.chatter.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chatter.app.R
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.MessageBody
import dev.chatter.app.chat.MessageKind
import dev.chatter.app.chat.Segment
import dev.chatter.app.chat.Tweet
import dev.chatter.app.chat.TweetMedia
import dev.chatter.app.settings.Settings
import dev.chatter.app.settings.TweetPictureSize
import dev.chatter.app.settings.TweetTextLines
import dev.chatter.app.ui.MainViewModel
import dev.chatter.app.ui.chat.MessageRow
import dev.chatter.app.ui.chat.TweetCardContent
import dev.chatter.app.ui.chat.rememberChatStyle
import dev.chatter.app.ui.chat.tweetCardStyle
import kotlin.math.roundToInt

internal val TWEET_PICTURE_SIZES = listOf(
    TweetPictureSize.Small to R.string.tweet_picture_small,
    TweetPictureSize.Medium to R.string.tweet_picture_medium,
    TweetPictureSize.Large to R.string.tweet_picture_large,
)

/** Posts from X: whether links become cards, and what a card shows. [TweetPreview] sits above it. */
@Composable
internal fun TweetsPage(settings: Settings, vm: MainViewModel) {
    SettingsGroup {
        item(R.string.settings_tweet_previews) {
            SwitchItem(R.string.settings_tweet_previews, settings.tweetPreviews, vm::setTweetPreviews, R.string.settings_tweet_previews_hint)
        }
    }
    SettingsGroup(R.string.settings_group_tweet_parts) {
        item(R.string.settings_tweet_avatar) { SwitchItem(R.string.settings_tweet_avatar, settings.tweetAvatar, vm::setTweetAvatar) }
        item(R.string.settings_tweet_date) { SwitchItem(R.string.settings_tweet_date, settings.tweetDate, vm::setTweetDate) }
        item { TextLinesItem(settings.tweetTextLines, vm) }
        item(R.string.settings_tweet_media) { SwitchItem(R.string.settings_tweet_media, settings.tweetMedia, vm::setTweetMedia) }
        if (settings.tweetMedia) {
            item(R.string.settings_tweet_picture_size) { PictureSizeItem(settings.tweetPictureSize, vm) }
        }
        item(R.string.settings_tweet_counts) { SwitchItem(R.string.settings_tweet_counts, settings.tweetCounts, vm::setTweetCounts) }
        item(R.string.settings_blur_sensitive_tweets) {
            SwitchItem(
                R.string.settings_blur_sensitive_tweets, settings.blurSensitiveTweets,
                vm::setBlurSensitiveTweets, R.string.settings_blur_sensitive_tweets_hint,
            )
        }
    }
}

/** Saved at every step, so the preview follows the thumb. */
@Composable
private fun TextLinesItem(lines: Int, vm: MainViewModel) {
    var value by remember(lines) { mutableFloatStateOf(lines.toFloat()) }
    val shown = value.roundToInt()
    val title = when (shown) {
        TweetTextLines.NONE -> stringResource(R.string.settings_tweet_text_none)
        TweetTextLines.ALL -> stringResource(R.string.settings_tweet_text_all)
        else -> pluralStringResource(R.plurals.settings_tweet_text_lines, shown, shown)
    }
    SliderItem(
        title = title,
        value = value,
        onChange = {
            value = it
            if (it.roundToInt() != lines) vm.setTweetTextLines(it.roundToInt())
        },
        onDone = {},
        range = TweetTextLines.NONE.toFloat()..TweetTextLines.ALL.toFloat(),
        steps = TweetTextLines.ALL - TweetTextLines.NONE - 1,
    )
}

@Composable
private fun PictureSizeItem(selected: TweetPictureSize, vm: MainViewModel) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_tweet_picture_size)) },
        supportingContent = {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                TWEET_PICTURE_SIZES.forEachIndexed { i, (size, label) ->
                    SegmentedButton(
                        selected = selected == size,
                        onClick = { vm.setTweetPictureSize(size) },
                        shape = SegmentedButtonDefaults.itemShape(i, TWEET_PICTURE_SIZES.size),
                    ) { Text(stringResource(label)) }
                }
            }
        },
        colors = transparentItem(),
    )
}

/**
 * A made-up message with a post card, drawn with the current settings. Pictures are colored
 * shapes, so the preview fetches nothing.
 */
@Composable
internal fun TweetPreview(settings: Settings, vm: MainViewModel) {
    val scheme = MaterialTheme.colorScheme
    val style = rememberChatStyle(settings, emptyMap())
    val messageText = stringResource(R.string.tweet_preview_message)
    val message = remember(messageText) {
        ChatItem(
            id = "preview", channel = "", kind = MessageKind.Chat, timestamp = System.currentTimeMillis(),
            login = "chatter", displayName = "Chatter", color = 0xFF1E90FF.toInt(),
            body = MessageBody.of(listOf(Segment.Text(messageText))), text = messageText,
        )
    }
    val postText = stringResource(R.string.tweet_preview_text)
    val post = remember(postText) {
        Tweet(
            name = "Chatter", handle = "chatter_app", avatarUrl = PREVIEW_AVATAR, text = postText,
            replies = 42, reposts = 128, likes = 1337, postedAt = System.currentTimeMillis(), sensitive = false,
            media = TweetMedia(PREVIEW_PICTURE, video = false, aspectRatio = PREVIEW_PICTURE_ASPECT, more = 0),
        )
    }
    val pictureBrush = Brush.linearGradient(listOf(scheme.primary, scheme.tertiary))
    Column {
        Text(
            stringResource(R.string.settings_tweet_preview),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.primary,
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
        )
        Surface(color = scheme.surfaceContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                MessageRow(message, style, vm.imageLoader, onGesture = null)
                Box(Modifier.padding(horizontal = 8.dp)) {
                    TweetCardContent(post, tweetCardStyle(settings), style, open = {}) { url, _, modifier ->
                        Box(if (url == PREVIEW_AVATAR) modifier.background(scheme.secondary) else modifier.background(pictureBrush))
                    }
                }
            }
        }
    }
}

private const val PREVIEW_AVATAR = "avatar"
private const val PREVIEW_PICTURE = "picture"
/** Tall enough that every picture size cuts it differently. */
private const val PREVIEW_PICTURE_ASPECT = 4f / 3f
