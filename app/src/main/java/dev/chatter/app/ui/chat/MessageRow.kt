package dev.chatter.app.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import dev.chatter.app.R
import dev.chatter.app.badges.Badge
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.ImageLinks
import dev.chatter.app.chat.MessageKind
import dev.chatter.app.chat.Segment
import dev.chatter.app.settings.TimestampFormat
import dev.chatter.app.ui.theme.NameColorPalette
import dev.chatter.app.ui.theme.readableNameColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything a message row needs besides the message itself. Changes rarely. */
@Immutable
data class ChatStyle(
    val fontSize: Float,
    val timestamps: TimestampFormat,
    val dark: Boolean,
    val secondaryText: Color,
    val linkColor: Color,
    val mentionBackground: Color,
    /** Background of every other message, or null when alternating backgrounds are off. */
    val alternateBackground: Color?,
    val noticeBackground: Color,
    /** Background of a chatter's first message, or null when they are not highlighted. */
    val firstMessageBackground: Color?,
    val accent: Color,
    /** How name colors are adjusted for readability. */
    val nameColors: NameColorPalette,
    /** Names the user gave chatters, by lowercase login. Usually empty. */
    val nicknames: Map<String, String>,
    /** Whether holding a message is answered with a short vibration. */
    val haptics: Boolean,
    /**
     * The hosts whose image links are shown as the image itself. Empty when the user turned
     * linked images off, which is the same thing as allowing nobody.
     */
    val imageHosts: List<String>,
)

/**
 * Which channel a message was written in, for a list that mixes several: the picture in front of
 * the line, and the name that stands in for it where a picture cannot be seen.
 */
@Immutable
data class ChannelMark(val avatarUrl: String?, val name: String)

private const val BADGE_EM = 1.35f
/** How strongly a rule's highlight color tints the message background. */
private const val HIGHLIGHT_ALPHA = 0.2f
/** Messages loaded from history are clearly dimmed so live chat stands out. */
private const val HISTORICAL_ALPHA = 0.5f
private const val EMOTE_EM = 2.1f
private const val EMOTE_FRAME_RATE = 30f
/** Big enough to see what was linked, small enough that one picture is not the whole screen. */
private val IMAGE_MAX_WIDTH = 220.dp
private val IMAGE_MAX_HEIGHT = 180.dp
/**
 * What a picture may take up when it is not the only one in the line. Small enough that two of
 * them fit beside each other on the narrowest phone, which is what makes the row wrap only when
 * the pictures really do not fit.
 */
private val IMAGE_SHARED_MAX_WIDTH = 150.dp
private val IMAGE_SHARED_MAX_HEIGHT = 130.dp
private val IMAGE_GAP = 4.dp

private class BuiltLine(
    val text: AnnotatedString,
    val inline: Map<String, InlineData>,
    /** Urls of the images that were taken out of the line and are drawn under it. */
    val images: List<String> = emptyList(),
)

private sealed interface InlineData {
    data class ChannelData(val mark: ChannelMark) : InlineData
    data class BadgeData(val badge: Badge) : InlineData
    data class EmoteData(val seg: Segment.EmoteSeg) : InlineData
}

// One formatter per pattern and thread: they are not safe to share across threads.
private val timeFormats = object : ThreadLocal<MutableMap<String, SimpleDateFormat>>() {
    override fun initialValue() = mutableMapOf<String, SimpleDateFormat>()
}

private fun formatTime(pattern: String, at: Long): String =
    timeFormats.get()!!.getOrPut(pattern) { SimpleDateFormat(pattern, Locale.getDefault()) }.format(Date(at))

/** How a message was touched. What each of them does is the screen's to decide. */
enum class MessageGesture { Tap, NameTap, Hold }

/**
 * One message. [onGesture] hears about taps and holds, and about a tap on the name as a gesture
 * of its own; without it the row is only something to look at, as in the user card. [channel]
 * puts the picture of the channel in front, for a list that mixes several.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageRow(
    item: ChatItem,
    style: ChatStyle,
    imageLoader: ImageLoader,
    onGesture: ((ChatItem, MessageGesture) -> Unit)?,
    onEmoteClick: ((Segment.EmoteSeg) -> Unit)? = null,
    channel: ChannelMark? = null,
) {
    val currentItem by rememberUpdatedState(item)
    val currentGesture by rememberUpdatedState(onGesture)
    // The name is a link inside the text, so that a tap on it is told apart from one on the words.
    // Kept the same object for good: a new one would build the line again on every recomposition.
    val nameTap = remember { LinkInteractionListener { currentGesture?.invoke(currentItem, MessageGesture.NameTap) } }
    val nameClickable = onGesture != null && item.login != null
    val built = remember(item, style, nameClickable, channel) { buildLine(item, style, nameTap.takeIf { nameClickable }, channel) }
    // Stable wrapper, so a new callback instance doesn't rebuild the inline content.
    val currentEmoteClick by rememberUpdatedState(onEmoteClick)
    val emoteClick = remember { { seg: Segment.EmoteSeg -> currentEmoteClick?.invoke(seg); Unit } }
    // Reading measured sizes here makes the row re-layout once a BTTV emote's real width is known.
    val measured = built.inline.values.mapNotNull { data ->
        (data as? InlineData.EmoteData)?.seg?.takeIf { s -> !s.emote.sizeKnown || s.overlays.any { !it.sizeKnown } }
            ?.let { s -> (s.overlays + s.emote).maxOf { EmoteSizes.aspectRatio(it) } }
    }
    val inlineContent = remember(built, imageLoader, measured) {
        built.inline.mapValues { (_, data) -> inlineFor(data, imageLoader, emoteClick.takeIf { onEmoteClick != null }) }
    }

    val firstMessage = item.isFirstMessage && style.firstMessageBackground != null
    val background = when {
        // A rule's own color beats the general mention color: the user picked it for this message.
        item.highlight != null -> Color(item.highlight).copy(alpha = HIGHLIGHT_ALPHA)
        item.isMention -> style.mentionBackground
        firstMessage -> style.firstMessageBackground!!
        item.kind == MessageKind.UserNotice -> style.noticeBackground
        item.alternate && style.alternateBackground != null -> style.alternateBackground
        else -> Color.Transparent
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(background)
            .then(
                if (onGesture == null) Modifier else Modifier.combinedClickable(
                    onClick = { onGesture(item, MessageGesture.Tap) },
                    onLongClick = { onGesture(item, MessageGesture.Hold) },
                    hapticFeedbackEnabled = style.haptics,
                ),
            )
            .padding(horizontal = 8.dp, vertical = 2.dp)
            // Modulating every draw instead of Modifier.alpha, which composites each dimmed row in
            // an offscreen layer of its own: with the history dimmed that was tens of thousands of
            // saveLayers while scrolling. Text and emotes never overlap, so both look the same.
            .graphicsLayer {
                alpha = when {
                    item.deleted -> 0.4f
                    item.historical -> HISTORICAL_ALPHA
                    else -> 1f
                }
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    ) {
        if (firstMessage) {
            Text(
                text = stringResource(R.string.first_message),
                color = style.accent,
                fontSize = (style.fontSize - 2).sp,
                fontWeight = FontWeight.Medium,
            )
        }
        item.reply?.let { reply ->
            Text(
                text = stringResource(R.string.reply_to, style.nameOf(reply.parentLogin, reply.parentDisplayName), reply.parentBody),
                color = style.secondaryText,
                fontSize = (style.fontSize - 2).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.kind == MessageKind.UserNotice && item.systemText != null) {
            // The channel goes in front of the header here: a sub or a raid often comes without a
            // message of its own, and then the header is all there is to the line.
            Text(
                text = remember(item.systemText, channel) {
                    buildAnnotatedString {
                        if (channel != null) appendChannel(channel)
                        append(item.systemText)
                    }
                },
                inlineContent = if (channel == null) emptyMap() else remember(channel, imageLoader) {
                    mapOf(CHANNEL_ID to inlineFor(InlineData.ChannelData(channel), imageLoader, null))
                },
                color = style.accent,
                fontSize = style.fontSize.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        if (built.text.isNotEmpty()) {
            Text(
                text = built.text,
                inlineContent = inlineContent,
                fontSize = style.fontSize.sp,
                lineHeight = (style.fontSize * 1.45f).sp,
            )
        }
        if (built.images.isNotEmpty()) {
            // Beside each other while they fit, and only then onto a line of their own: two
            // pictures in one message are usually meant to be looked at together.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(IMAGE_GAP),
                verticalArrangement = Arrangement.spacedBy(IMAGE_GAP),
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            ) {
                val alone = built.images.size == 1
                built.images.forEach { url -> LinkedImage(url, style, imageLoader, alone) }
            }
        }
    }
}

/**
 * An image somebody linked, in place of its url. Tapping it opens the link — which is also all
 * that is left when the picture cannot be fetched: a url the app cannot show is still one the
 * browser might. [alone] is the only picture in its message, and may take the width for it.
 */
@Composable
private fun LinkedImage(url: String, style: ChatStyle, loader: ImageLoader, alone: Boolean) {
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) {
        Text(
            text = remember(url, style.linkColor) { linkText(url, style) },
            fontSize = style.fontSize.sp,
            lineHeight = (style.fontSize * 1.45f).sp,
        )
        return
    }
    val uriHandler = LocalUriHandler.current
    AsyncImage(
        model = url,
        contentDescription = stringResource(R.string.linked_image),
        imageLoader = loader,
        contentScale = ContentScale.Fit,
        alignment = Alignment.TopStart,
        onError = { failed = true },
        modifier = Modifier
            .sizeIn(
                maxWidth = if (alone) IMAGE_MAX_WIDTH else IMAGE_SHARED_MAX_WIDTH,
                maxHeight = if (alone) IMAGE_MAX_HEIGHT else IMAGE_SHARED_MAX_HEIGHT,
            )
            .clip(RoundedCornerShape(8.dp))
            .clickable { uriHandler.openUri(url) },
    )
}

private fun linkText(url: String, style: ChatStyle) = buildAnnotatedString {
    withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = style.linkColor, textDecoration = TextDecoration.Underline)))) {
        append(url)
    }
}

private fun inlineFor(data: InlineData, loader: ImageLoader, onEmoteClick: ((Segment.EmoteSeg) -> Unit)?): InlineTextContent = when (data) {
    is InlineData.ChannelData -> InlineTextContent(
        Placeholder(BADGE_EM.em, BADGE_EM.em, PlaceholderVerticalAlign.Center),
    ) {
        AsyncImage(
            model = data.mark.avatarUrl,
            contentDescription = data.mark.name,
            imageLoader = loader,
            modifier = Modifier.fillMaxSize().clip(CircleShape),
        )
    }
    is InlineData.BadgeData -> InlineTextContent(
        Placeholder(BADGE_EM.em, BADGE_EM.em, PlaceholderVerticalAlign.Center),
    ) {
        AsyncImage(model = data.badge.url, contentDescription = data.badge.title, imageLoader = loader, modifier = Modifier.fillMaxSize())
    }
    is InlineData.EmoteData -> {
        val base = data.seg.emote
        // Wide zero-width overlays should not be clipped: use the widest aspect ratio.
        val aspect = (data.seg.overlays + base).maxOf { EmoteSizes.aspectRatio(it) }
        InlineTextContent(
            Placeholder((EMOTE_EM * aspect).em, EMOTE_EM.em, PlaceholderVerticalAlign.Center),
        ) {
            Box(Modifier.fillMaxSize().preferredFrameRate(EMOTE_FRAME_RATE).then(if (onEmoteClick != null) Modifier.clickable { onEmoteClick(data.seg) } else Modifier)) {
                (listOf(base) + data.seg.overlays).forEach { e ->
                    SharedEmoteImage(
                        url = e.url,
                        contentDescription = e.name,
                        loader = loader,
                        onLoaded = EmoteSizes.onSize(e),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** Where the channel's picture goes in a line; see [appendChannel]. */
private const val CHANNEL_ID = "c"

/** The picture of the channel a message was written in, in front of everything else in its line. */
private fun AnnotatedString.Builder.appendChannel(channel: ChannelMark) {
    appendInlineContent(CHANNEL_ID, channel.name)
    append(' ')
}

private fun buildLine(item: ChatItem, style: ChatStyle, onName: LinkInteractionListener?, channel: ChannelMark?): BuiltLine {
    val inline = HashMap<String, InlineData>()
    // A sub or a raid has the channel in front of its header instead, the line under it included.
    val lineChannel = channel.takeIf { item.kind != MessageKind.UserNotice }
    lineChannel?.let { inline[CHANNEL_ID] = InlineData.ChannelData(it) }
    val (segments, images) = ImageLinks.split(item.segments, style.imageHosts)
    if (item.kind == MessageKind.Notice) {
        val text = buildAnnotatedString {
            lineChannel?.let { appendChannel(it) }
            withStyle(SpanStyle(color = style.secondaryText, fontStyle = FontStyle.Italic)) {
                style.timestamps.pattern?.let { append(formatTime(it, item.timestamp) + " ") }
                append(item.systemText ?: item.text)
                if (segments.isNotEmpty()) {
                    append(' ')
                    appendSegments(segments, inline, style)
                }
            }
        }
        return BuiltLine(text, inline, images)
    }
    if (item.kind == MessageKind.UserNotice && item.segments.isEmpty()) return BuiltLine(AnnotatedString(""), inline)

    val nameColor = readableNameColor(item.color, item.login, style.dark, style.nameColors)
    val isAction = item.kind == MessageKind.Action
    val text = buildAnnotatedString {
        lineChannel?.let { appendChannel(it) }
        style.timestamps.pattern?.let { pattern ->
            withStyle(SpanStyle(color = style.secondaryText, fontSize = (style.fontSize - 2).sp)) {
                append(formatTime(pattern, item.timestamp))
            }
            append(' ')
        }
        item.badges.forEachIndexed { i, badge ->
            val id = "b$i"
            inline[id] = InlineData.BadgeData(badge)
            appendInlineContent(id, badge.title)
            append(' ')
        }
        withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.Bold)) {
            if (onName == null) append(displayName(item, style))
            // Looks no different from a name that cannot be tapped: every name can be, so marking
            // them would only be noise.
            else withLink(LinkAnnotation.Clickable("name", TextLinkStyles(SpanStyle()), onName)) {
                append(displayName(item, style))
            }
        }
        append(if (isAction) " " else ": ")

        val body = SpanStyle(
            color = if (isAction) nameColor else Color.Unspecified,
            fontStyle = if (isAction) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (item.deleted) TextDecoration.LineThrough else null,
        )
        withStyle(body) { appendSegments(segments, inline, style) }
    }
    return BuiltLine(text, inline, images)
}

private fun AnnotatedString.Builder.appendSegments(segments: List<Segment>, inline: MutableMap<String, InlineData>, style: ChatStyle) {
    segments.forEachIndexed { i, seg ->
        when (seg) {
            is Segment.Text -> append(seg.text)
            is Segment.EmoteSeg -> {
                val id = "e$i"
                inline[id] = InlineData.EmoteData(seg)
                appendInlineContent(id, seg.emote.name)
            }
            is Segment.Link -> withLink(
                LinkAnnotation.Url(seg.url, TextLinkStyles(SpanStyle(color = style.linkColor, textDecoration = TextDecoration.Underline))),
            ) { append(seg.text) }
            is Segment.Mention -> {
                val color = seg.login?.let { readableNameColor(seg.color, it, style.dark, style.nameColors) } ?: Color.Unspecified
                withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(seg.name) }
            }
        }
    }
}

/** The nickname the user gave [login], or [fallback] when they gave none. */
fun ChatStyle.nameOf(login: String?, fallback: String): String =
    login?.let { nicknames[it.lowercase()] } ?: fallback

/**
 * "Name" or "Name (login)" for localized display names like Japanese or Korean ones. A nickname
 * the user picked replaces both: they already know who they meant by it.
 */
private fun displayName(item: ChatItem, style: ChatStyle): String {
    val display = item.displayName ?: item.login ?: ""
    val login = item.login ?: return display
    style.nicknames[login.lowercase()]?.let { return it }
    return if (display.equals(login, ignoreCase = true)) display else "$display ($login)"
}
