package me.lesh.chatter.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.lesh.chatter.R
import me.lesh.chatter.auth.AuthState
import me.lesh.chatter.chat.ChatItem
import me.lesh.chatter.chat.MessageBody
import me.lesh.chatter.chat.MessageKind
import me.lesh.chatter.chat.Post
import me.lesh.chatter.chat.PostEmote
import me.lesh.chatter.chat.PostLabel
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
import me.lesh.chatter.ui.chat.over
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
    PostSite.Twitch -> R.string.settings_post_site_twitch_hint
    PostSite.SevenTv -> R.string.settings_post_site_seventv_hint
}

/** YouTube has videos, Twitch has channels and clips, and 7TV has emotes, not posts. */
@Composable
private fun siteTitle(site: PostSite): String = when (site) {
    PostSite.YouTube -> stringResource(R.string.settings_post_site_youtube)
    PostSite.Twitch -> stringResource(R.string.settings_post_site_twitch)
    PostSite.SevenTv -> stringResource(R.string.settings_post_site_seventv)
    else -> stringResource(R.string.settings_post_site, stringResource(siteName(site)))
}

/** The page of each site's own settings. */
internal fun postSitePage(site: PostSite): SettingsSubPage = when (site) {
    PostSite.X -> SettingsSubPage.PostsX
    PostSite.Bluesky -> SettingsSubPage.PostsBluesky
    PostSite.YouTube -> SettingsSubPage.PostsYouTube
    PostSite.Twitch -> SettingsSubPage.PostsTwitch
    PostSite.SevenTv -> SettingsSubPage.PostsSevenTv
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
    // Its cards have one look only.
    site == PostSite.SevenTv -> R.string.settings_post_site_on
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
    if (site == PostSite.SevenTv) {
        SevenTvAccountGroup(vm)
        return
    }
    SettingsGroup(R.string.settings_group_post_parts) {
        item(R.string.settings_post_compact) {
            SwitchItem(
                R.string.settings_post_compact, card.compact,
                { v -> update { it.copy(compact = v) } }, R.string.settings_post_compact_hint,
            )
        }
        val avatar = if (site.videos) R.string.settings_post_channel_picture else R.string.settings_post_avatar
        item(avatar) { SwitchItem(avatar, card.avatar, { v -> update { it.copy(avatar = v) } }) }
        item(R.string.settings_post_date) { SwitchItem(R.string.settings_post_date, card.date, { v -> update { it.copy(date = v) } }) }
        val text = if (site.videos) R.string.settings_post_title else R.string.settings_post_text
        item(text) { TextLinesItem(text, card.textLines) { lines -> update { it.copy(textLines = lines) } } }
        val media = if (site.videos) R.string.settings_post_thumbnail else R.string.settings_post_media
        item(media) { SwitchItem(media, card.media, { v -> update { it.copy(media = v) } }) }
        if (card.media) {
            item(R.string.settings_post_picture_size) {
                PictureSizeItem(card.pictureSize) { size -> update { it.copy(pictureSize = size) } }
            }
        }
        val counts = when (site) {
            PostSite.YouTube -> R.string.settings_post_views
            PostSite.Twitch -> R.string.settings_post_twitch_counts
            else -> R.string.settings_post_counts
        }
        item(counts) { SwitchItem(counts, card.counts, { v -> update { it.copy(counts = v) } }) }
    }
}

/**
 * The 7TV login that adding emotes from their cards needs: first whether there is one, with an
 * icon so it shows at a glance, then the button that changes it.
 */
@Composable
private fun SevenTvAccountGroup(vm: MainViewModel) {
    val loggedIn by vm.sevenTvLoggedIn.collectAsStateWithLifecycle()
    // A 7TV login belongs to a Twitch account; a guest has none to keep it for.
    val auth by vm.authState.collectAsStateWithLifecycle()
    if (auth !is AuthState.LoggedIn) return
    val scheme = MaterialTheme.colorScheme
    SettingsGroup(R.string.settings_group_seventv_status) {
        item(R.string.settings_group_seventv_status) {
            ListItem(
                leadingContent = {
                    Icon(
                        if (loggedIn) Icons.Default.CheckCircle else Icons.Outlined.Info,
                        contentDescription = null,
                        tint = if (loggedIn) scheme.primary else scheme.onSurfaceVariant,
                    )
                },
                headlineContent = {
                    Text(stringResource(if (loggedIn) R.string.settings_seventv_logged_in else R.string.settings_seventv_logged_out))
                },
                supportingContent = {
                    Text(stringResource(if (loggedIn) R.string.settings_seventv_logged_in_hint else R.string.settings_seventv_logged_out_hint))
                },
                colors = transparentItem(),
            )
        }
        item(R.string.settings_seventv_login) {
            ListItem(
                headlineContent = { Text(stringResource(if (loggedIn) R.string.settings_seventv_logout else R.string.settings_seventv_login)) },
                supportingContent = {
                    Text(stringResource(if (loggedIn) R.string.settings_seventv_logout_hint else R.string.settings_seventv_login_hint))
                },
                trailingContent = {
                    if (loggedIn) {
                        OutlinedButton(onClick = vm::logoutSevenTv) { Text(stringResource(R.string.settings_seventv_logout_button)) }
                    } else {
                        Button(onClick = { vm.openSevenTvLogin() }) { Text(stringResource(R.string.settings_seventv_login_button)) }
                    }
                },
                colors = transparentItem(),
            )
        }
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
    val defaultText = when (site) {
        PostSite.YouTube -> R.string.post_preview_video
        PostSite.Twitch -> R.string.post_preview_clip
        else -> R.string.post_preview_text
    }
    val postText = stringResource(text ?: defaultText)
    val post = remember(postText, site) {
        if (site == PostSite.SevenTv) {
            Post(
                name = PREVIEW_EMOTE_NAME, handle = "", avatarUrl = null, text = "",
                replies = 0, reposts = 0, likes = 0, postedAt = 0, nsfw = false, media = null,
                emote = PostEmote(id = "preview", pictureUrl = PREVIEW_PICTURE, creator = "Chatter", aspectRatio = 1f),
            )
        } else if (site.videos) {
            Post(
                name = "Chatter", handle = "", avatarUrl = PREVIEW_AVATAR, text = postText,
                replies = 0, reposts = 0, likes = 0, views = 13370, postedAt = System.currentTimeMillis(), nsfw = false,
                label = PostLabel.Clip.takeIf { site == PostSite.Twitch },
                media = PostMedia(
                    PREVIEW_PICTURE, video = true, aspectRatio = 16f / 9f, more = 0,
                    lengthSeconds = if (site == PostSite.Twitch) PREVIEW_CLIP_SECONDS else PREVIEW_VIDEO_SECONDS,
                ),
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
                MessageRow(message, style.over(scheme.surfaceContainer), vm.imageLoader, onGesture = null)
                Box(Modifier.padding(horizontal = 8.dp)) {
                    PostCardContent(post, site, postCardStyle(settings), style, open = {}, action = previewAction(site)) { url, _, modifier ->
                        Box(if (url == PREVIEW_AVATAR) modifier.background(scheme.secondary) else modifier.background(pictureBrush))
                    }
                }
            }
        }
    }
}

/** The add button an emote's card has for the channel's editors, drawn only, so they know where it is. */
private fun previewAction(site: PostSite): (@Composable () -> Unit)? = if (site != PostSite.SevenTv) null else {
    {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Add, contentDescription = null)
        }
    }
}

/** How the made-up author is written on each site. */
private val PREVIEW_HANDLES = mapOf(PostSite.X to "chatter_app", PostSite.Bluesky to "chatter.bsky.social")
private const val PREVIEW_AVATAR = "avatar"
private const val PREVIEW_EMOTE_NAME = "chatterHype"
private const val PREVIEW_PICTURE = "picture"
private const val PREVIEW_VIDEO_SECONDS = 213
private const val PREVIEW_CLIP_SECONDS = 30
/** Tall enough that every picture size cuts it differently. */
private const val PREVIEW_PICTURE_ASPECT = 4f / 3f
