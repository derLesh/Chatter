package dev.chatter.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import dev.chatter.app.chat.Post
import dev.chatter.app.chat.PostMedia
import dev.chatter.app.chat.PostSite
import dev.chatter.app.chat.Segment
import dev.chatter.app.settings.PostPictureSize
import dev.chatter.app.settings.PostTextLines
import dev.chatter.app.settings.Settings
import dev.chatter.app.ui.MainViewModel
import dev.chatter.app.ui.chat.MessageRow
import dev.chatter.app.ui.chat.PostCardContent
import dev.chatter.app.ui.chat.postCardStyle
import dev.chatter.app.ui.chat.rememberChatStyle
import dev.chatter.app.ui.chat.siteName
import kotlin.math.roundToInt

internal val POST_PICTURE_SIZES = listOf(
    PostPictureSize.Small to R.string.post_picture_small,
    PostPictureSize.Medium to R.string.post_picture_medium,
    PostPictureSize.Large to R.string.post_picture_large,
)

/** What turning a site on means, above all where its posts are fetched from. */
private fun siteHint(site: PostSite): Int = when (site) {
    PostSite.X -> R.string.settings_post_site_x_hint
    PostSite.Bluesky -> R.string.settings_post_site_bluesky_hint
}

/** Social media posts: which sites' links become cards, and what a card shows, with a preview of one. */
@Composable
internal fun PostsPage(settings: Settings, vm: MainViewModel) {
    SettingsGroup {
        PostSite.entries.forEach { site ->
            item { SiteItem(site, site in settings.postSites) { vm.setPostSite(site, it) } }
        }
    }
    PostPreview(settings, vm)
    SettingsGroup(R.string.settings_group_post_parts) {
        item(R.string.settings_post_avatar) { SwitchItem(R.string.settings_post_avatar, settings.postAvatar, vm::setPostAvatar) }
        item(R.string.settings_post_date) { SwitchItem(R.string.settings_post_date, settings.postDate, vm::setPostDate) }
        item { TextLinesItem(settings.postTextLines, vm) }
        item(R.string.settings_post_media) { SwitchItem(R.string.settings_post_media, settings.postMedia, vm::setPostMedia) }
        if (settings.postMedia) {
            item(R.string.settings_post_picture_size) { PictureSizeItem(settings.postPictureSize, vm) }
        }
        item(R.string.settings_post_counts) { SwitchItem(R.string.settings_post_counts, settings.postCounts, vm::setPostCounts) }
        item(R.string.settings_blur_nsfw_posts) {
            SwitchItem(
                R.string.settings_blur_nsfw_posts, settings.blurNsfwPosts,
                vm::setBlurNsfwPosts, R.string.settings_blur_nsfw_posts_hint,
            )
        }
    }
}

@Composable
internal fun SiteItem(site: PostSite, on: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_post_site, stringResource(siteName(site)))) },
        supportingContent = { Text(stringResource(siteHint(site))) },
        trailingContent = { Switch(checked = on, onCheckedChange = onChange) },
        colors = transparentItem(),
        modifier = Modifier.clickable { onChange(!on) },
    )
}

/** Saved at every step, so the preview follows the thumb. */
@Composable
private fun TextLinesItem(lines: Int, vm: MainViewModel) {
    var value by remember(lines) { mutableFloatStateOf(lines.toFloat()) }
    val shown = value.roundToInt()
    val title = when (shown) {
        PostTextLines.NONE -> stringResource(R.string.settings_post_text_none)
        PostTextLines.ALL -> stringResource(R.string.settings_post_text_all)
        else -> pluralStringResource(R.plurals.settings_post_text_lines, shown, shown)
    }
    SliderItem(
        title = title,
        value = value,
        onChange = {
            value = it
            if (it.roundToInt() != lines) vm.setPostTextLines(it.roundToInt())
        },
        onDone = {},
        range = PostTextLines.NONE.toFloat()..PostTextLines.ALL.toFloat(),
        steps = PostTextLines.ALL - PostTextLines.NONE - 1,
    )
}

@Composable
private fun PictureSizeItem(selected: PostPictureSize, vm: MainViewModel) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_post_picture_size)) },
        supportingContent = {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                POST_PICTURE_SIZES.forEachIndexed { i, (size, label) ->
                    SegmentedButton(
                        selected = selected == size,
                        onClick = { vm.setPostPictureSize(size) },
                        shape = SegmentedButtonDefaults.itemShape(i, POST_PICTURE_SIZES.size),
                    ) { Text(stringResource(label)) }
                }
            }
        },
        colors = transparentItem(),
    )
}

/**
 * A made-up message with a post card, drawn with the current settings, from the first site turned
 * on. Pictures are colored shapes, so the preview fetches nothing. [text] is what the post says.
 */
@Composable
internal fun PostPreview(settings: Settings, vm: MainViewModel, text: Int = R.string.post_preview_text) {
    val scheme = MaterialTheme.colorScheme
    val style = rememberChatStyle(settings, emptyMap())
    val site = settings.postSites.firstOrNull() ?: PostSite.X
    val messageText = stringResource(R.string.post_preview_message)
    val message = remember(messageText) {
        ChatItem(
            id = "preview", channel = "", kind = MessageKind.Chat, timestamp = System.currentTimeMillis(),
            login = "chatter", displayName = "Chatter", color = 0xFF1E90FF.toInt(),
            body = MessageBody.of(listOf(Segment.Text(messageText))), text = messageText,
        )
    }
    val postText = stringResource(text)
    val post = remember(postText, site) {
        Post(
            name = "Chatter", handle = PREVIEW_HANDLES.getValue(site), avatarUrl = PREVIEW_AVATAR, text = postText,
            replies = 42, reposts = 128, likes = 1337, postedAt = System.currentTimeMillis(), nsfw = false,
            media = PostMedia(PREVIEW_PICTURE, video = false, aspectRatio = PREVIEW_PICTURE_ASPECT, more = 0),
        )
    }
    val pictureBrush = Brush.linearGradient(listOf(scheme.primary, scheme.tertiary))
    Column {
        Text(
            stringResource(R.string.settings_post_preview),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.primary,
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
        )
        Surface(color = scheme.surfaceContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                MessageRow(message, style, vm.imageLoader, onGesture = null)
                Box(Modifier.padding(horizontal = 8.dp)) {
                    PostCardContent(post, site, postCardStyle(settings), style, open = {}) { url, _, modifier ->
                        Box(if (url == PREVIEW_AVATAR) modifier.background(scheme.secondary) else modifier.background(pictureBrush))
                    }
                }
            }
        }
    }
}

/** How the made-up author is written on each site. */
private val PREVIEW_HANDLES = mapOf(PostSite.X to "chatter_app", PostSite.Bluesky to "chatter.bsky.social")
private const val PREVIEW_AVATAR = "avatar"
private const val PREVIEW_PICTURE = "picture"
/** Tall enough that every picture size cuts it differently. */
private const val PREVIEW_PICTURE_ASPECT = 4f / 3f
