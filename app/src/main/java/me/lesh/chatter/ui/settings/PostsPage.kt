package me.lesh.chatter.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.lesh.chatter.R
import me.lesh.chatter.chat.ChatItem
import me.lesh.chatter.chat.MessageBody
import me.lesh.chatter.chat.MessageKind
import me.lesh.chatter.chat.Post
import me.lesh.chatter.chat.PostMedia
import me.lesh.chatter.chat.PostSite
import me.lesh.chatter.chat.Segment
import me.lesh.chatter.settings.PostCardSettings
import me.lesh.chatter.settings.PostPictureSize
import me.lesh.chatter.settings.PostTextLines
import me.lesh.chatter.settings.Settings
import me.lesh.chatter.settings.postCard
import me.lesh.chatter.ui.MainViewModel
import me.lesh.chatter.ui.SettingsSubPage
import me.lesh.chatter.ui.chat.MessageRow
import me.lesh.chatter.ui.chat.PostCardContent
import me.lesh.chatter.ui.chat.postCardStyle
import me.lesh.chatter.ui.chat.rememberChatStyle
import me.lesh.chatter.ui.chat.siteIcon
import me.lesh.chatter.ui.chat.siteName
import me.lesh.chatter.ui.chat.siteTint
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
    PostSite.YouTube -> R.string.settings_post_site_youtube_hint
}

/** YouTube has videos, not posts. */
@Composable
private fun siteTitle(site: PostSite): String = when (site) {
    PostSite.YouTube -> stringResource(R.string.settings_post_site_youtube)
    else -> stringResource(R.string.settings_post_site, stringResource(siteName(site)))
}

/** The page of each site's own settings. */
internal fun postSitePage(site: PostSite): SettingsSubPage = when (site) {
    PostSite.X -> SettingsSubPage.PostsX
    PostSite.Bluesky -> SettingsSubPage.PostsBluesky
    PostSite.YouTube -> SettingsSubPage.PostsYouTube
}

/** Social media posts: one entry per site, each opening its own page, and what holds for all of them. */
@Composable
internal fun PostsPage(settings: Settings, vm: MainViewModel, open: (SettingsSubPage) -> Unit) {
    SettingsGroup {
        PostSite.entries.forEach { site ->
            item(siteName(site)) {
                ListItem(
                    headlineContent = { Text(stringResource(siteName(site))) },
                    supportingContent = { Text(stringResource(siteSummary(site, settings))) },
                    leadingContent = {
                        Icon(
                            painterResource(siteIcon(site)),
                            contentDescription = null,
                            tint = siteTint(site, MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    trailingContent = { NavChevron() },
                    colors = transparentItem(),
                    modifier = Modifier.tileClickable { open(postSitePage(site)) },
                )
            }
        }
    }
    SettingsGroup(R.string.settings_group_post_all) {
        item(R.string.settings_blur_nsfw_posts) {
            SwitchItem(
                R.string.settings_blur_nsfw_posts, settings.blurNsfwPosts,
                vm::setBlurNsfwPosts, R.string.settings_blur_nsfw_posts_hint,
            )
        }
    }
}

private fun siteSummary(site: PostSite, settings: Settings): Int = when {
    site !in settings.postSites -> R.string.settings_off
    settings.postCard(site).compact -> R.string.settings_post_site_on_compact
    else -> R.string.settings_post_site_on
}

/** One site: whether its links become cards, and what its cards show, with a preview of one. */
@Composable
internal fun PostSitePage(site: PostSite, settings: Settings, vm: MainViewModel) {
    val on = site in settings.postSites
    SettingsGroup { item { SiteItem(site, on) { vm.setPostSite(site, it) } } }
    if (!on) return
    val card = settings.postCard(site)
    val update = { change: (PostCardSettings) -> PostCardSettings -> vm.updatePostCard(site, change) }
    PostPreview(settings, vm, site = site)
    SettingsGroup(R.string.settings_group_post_parts) {
        item(R.string.settings_post_compact) {
            SwitchItem(
                R.string.settings_post_compact, card.compact,
                { v -> update { it.copy(compact = v) } }, R.string.settings_post_compact_hint,
            )
        }
        val avatar = if (site == PostSite.YouTube) R.string.settings_post_channel_picture else R.string.settings_post_avatar
        item(avatar) { SwitchItem(avatar, card.avatar, { v -> update { it.copy(avatar = v) } }) }
        item(R.string.settings_post_date) { SwitchItem(R.string.settings_post_date, card.date, { v -> update { it.copy(date = v) } }) }
        val text = if (site == PostSite.YouTube) R.string.settings_post_title else R.string.settings_post_text
        item(text) { TextLinesItem(text, card.textLines) { lines -> update { it.copy(textLines = lines) } } }
        val media = if (site == PostSite.YouTube) R.string.settings_post_thumbnail else R.string.settings_post_media
        item(media) { SwitchItem(media, card.media, { v -> update { it.copy(media = v) } }) }
        if (card.media) {
            item(R.string.settings_post_picture_size) {
                PictureSizeItem(card.pictureSize) { size -> update { it.copy(pictureSize = size) } }
            }
        }
        val counts = if (site == PostSite.YouTube) R.string.settings_post_views else R.string.settings_post_counts
        item(counts) { SwitchItem(counts, card.counts, { v -> update { it.copy(counts = v) } }) }
    }
}

@Composable
internal fun SiteItem(site: PostSite, on: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(siteTitle(site)) },
        supportingContent = { Text(stringResource(siteHint(site))) },
        trailingContent = { SettingsSwitch(checked = on, onCheckedChange = onChange) },
        colors = transparentItem(),
        modifier = Modifier.tileClickable { onChange(!on) },
    )
}

/** Saved at every step, so the preview follows the thumb. */
@Composable
private fun TextLinesItem(title: Int, lines: Int, onChange: (Int) -> Unit) {
    var value by remember(lines) { mutableFloatStateOf(lines.toFloat()) }
    val shown = value.roundToInt()
    val valueLabel = when (shown) {
        PostTextLines.NONE -> stringResource(R.string.settings_post_text_none)
        PostTextLines.ALL -> stringResource(R.string.settings_post_text_all)
        else -> pluralStringResource(R.plurals.settings_post_text_lines, shown, shown)
    }
    SliderItem(
        title = title,
        valueLabel = valueLabel,
        value = value,
        onChange = {
            value = it
            if (it.roundToInt() != lines) onChange(it.roundToInt())
        },
        onDone = {},
        range = PostTextLines.NONE.toFloat()..PostTextLines.ALL.toFloat(),
        steps = PostTextLines.ALL - PostTextLines.NONE - 1,
    )
}

@Composable
private fun PictureSizeItem(selected: PostPictureSize, onChange: (PostPictureSize) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_post_picture_size)) },
        supportingContent = {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                POST_PICTURE_SIZES.forEachIndexed { i, (size, label) ->
                    SegmentedButton(
                        selected = selected == size,
                        onClick = { onChange(size) },
                        shape = SegmentedButtonDefaults.itemShape(i, POST_PICTURE_SIZES.size),
                    ) { Text(stringResource(label)) }
                }
            }
        },
        colors = transparentItem(),
    )
}

/**
 * A made-up message with a post card from [site], drawn with the current settings; without one,
 * from the first site turned on. Pictures are colored shapes, so the preview fetches nothing.
 * [text] is what the post says, or the video's title.
 */
@Composable
internal fun PostPreview(settings: Settings, vm: MainViewModel, text: Int? = null, site: PostSite? = null) {
    val scheme = MaterialTheme.colorScheme
    val style = rememberChatStyle(settings, emptyMap())
    val site = site ?: settings.postSites.firstOrNull() ?: PostSite.X
    val messageText = stringResource(R.string.post_preview_message)
    val message = remember(messageText) {
        ChatItem(
            id = "preview", channel = "", kind = MessageKind.Chat, timestamp = System.currentTimeMillis(),
            login = "chatter", displayName = "Chatter", color = 0xFF1E90FF.toInt(),
            body = MessageBody.of(listOf(Segment.Text(messageText))), text = messageText,
        )
    }
    val postText = stringResource(text ?: if (site == PostSite.YouTube) R.string.post_preview_video else R.string.post_preview_text)
    val post = remember(postText, site) {
        if (site == PostSite.YouTube) {
            Post(
                name = "Chatter", handle = "", avatarUrl = PREVIEW_AVATAR, text = postText,
                replies = 0, reposts = 0, likes = 0, views = 13370, postedAt = System.currentTimeMillis(), nsfw = false,
                media = PostMedia(PREVIEW_PICTURE, video = true, aspectRatio = 16f / 9f, more = 0, lengthSeconds = 213),
            )
        } else {
            Post(
                name = "Chatter", handle = PREVIEW_HANDLES.getValue(site), avatarUrl = PREVIEW_AVATAR, text = postText,
                replies = 42, reposts = 128, likes = 1337, postedAt = System.currentTimeMillis(), nsfw = false,
                media = PostMedia(PREVIEW_PICTURE, video = false, aspectRatio = PREVIEW_PICTURE_ASPECT, more = 0),
            )
        }
    }
    val pictureBrush = Brush.linearGradient(listOf(scheme.primary, scheme.tertiary))
    Column {
        Text(
            stringResource(R.string.settings_post_preview),
            style = MaterialTheme.typography.titleSmall,
            color = scheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        Surface(color = scheme.surfaceContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            // In line with the text of the tiles around it.
            Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
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
