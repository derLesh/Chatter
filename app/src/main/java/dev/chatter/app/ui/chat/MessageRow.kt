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
import androidx.compose.foundation.text.appendInlineContent
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
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
import dev.chatter.app.chat.LinkText
import dev.chatter.app.chat.MessageKind
import dev.chatter.app.chat.Segment
import dev.chatter.app.settings.TimestampFormat
import dev.chatter.app.ui.theme.NameColorPalette
import dev.chatter.app.ui.theme.readableNameColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What a message row needs besides the message. Changes rarely. */
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
    /** Background of a chatter's first message, or null when not highlighted. */
    val firstMessageBackground: Color?,
    val accent: Color,
    /** How name colors are adjusted for readability. */
    val nameColors: NameColorPalette,
    /** Nicknames by lowercase login. */
    val nicknames: Map<String, String>,
    /** Whether holding a message vibrates. */
    val haptics: Boolean,
    /** Hosts whose image links are shown as images. Empty when linked images are off. */
    val imageHosts: List<String>,
    /** Whether long links are shortened to site and path start; see [LinkText]. */
    val shortLinks: Boolean = true,
    /** Smallest emote size, to save data; see [dev.chatter.app.net.DataSaving]. */
    val smallEmotes: Boolean = false,
    /** Frame rate animated emotes ask for; see [EmoteFrameRate]. */
    val emoteFrameRate: Float = EmoteFrameRate.ACTIVE,
)

/**
 * The channel a message was written in, for lists that mix channels: its picture, and its name for
 * accessibility.
 */
@Immutable
data class ChannelMark(val avatarUrl: String?, val name: String)

private const val BADGE_EM = 1.35f
/** How strongly a rule's highlight color tints the background. */
private const val HIGHLIGHT_ALPHA = 0.2f
/** History is dimmed so live chat stands out. */
private const val HISTORICAL_ALPHA = 0.5f
private const val EMOTE_EM = 2.1f
/** Large enough to recognize the picture, small enough not to fill the screen. */
private val IMAGE_MAX_WIDTH = 220.dp
private val IMAGE_MAX_HEIGHT = 180.dp
/**
 * Size of a picture that shares the row. Two fit side by side on the narrowest phone, so the row
 * only wraps when they really do not fit.
 */
private val IMAGE_SHARED_MAX_WIDTH = 150.dp
private val IMAGE_SHARED_MAX_HEIGHT = 130.dp
private val IMAGE_GAP = 4.dp

private class BuiltLine(
    val text: AnnotatedString,
    val inline: Map<String, InlineData>,
    /** URLs of images taken out of the line and drawn below it. */
    val images: List<String> = emptyList(),
)

/**
 * Aspect ratios of emotes in [line] whose size is not known yet. Reading them while composing
 * relayouts the row once a BTTV emote's size is known.
 */
private fun pendingAspects(line: BuiltLine): List<Float> = line.inline.values.mapNotNull { data ->
    (data as? InlineData.EmoteData)?.seg?.takeIf { s -> !s.emote.sizeKnown || s.overlays.any { !it.sizeKnown } }
        ?.let { s -> (s.overlays + s.emote).maxOf { EmoteSizes.aspectRatio(it) } }
}

private sealed interface InlineData {
    data class ChannelData(val mark: ChannelMark) : InlineData
    data class BadgeData(val badge: Badge) : InlineData
    data class EmoteData(val seg: Segment.EmoteSeg) : InlineData
}

// SimpleDateFormat is not thread-safe: one per pattern and thread.
private val timeFormats = object : ThreadLocal<MutableMap<String, SimpleDateFormat>>() {
    override fun initialValue() = mutableMapOf<String, SimpleDateFormat>()
}

private fun formatTime(pattern: String, at: Long): String =
    timeFormats.get()!!.getOrPut(pattern) { SimpleDateFormat(pattern, Locale.getDefault()) }.format(Date(at))

/**
 * How a message was touched; the screen decides what each does. [Thread] is a tap on the reply
 * line.
 */
enum class MessageGesture { Tap, NameTap, Hold, Thread }

/**
 * One message. [onGesture] receives taps, holds and name taps; without it the row is display only,
 * as in the user card. [channel] shows the channel's picture in front, for mixed lists.
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
    // The name is a link inside the text, so a tap on it is distinguishable from one on the
    // message. Kept stable; a new instance would rebuild the line on every recomposition.
    val nameTap = remember { LinkInteractionListener { currentGesture?.invoke(currentItem, MessageGesture.NameTap) } }
    val nameClickable = onGesture != null && item.login != null
    val built = remember(item, style, nameClickable, channel) { buildLine(item, style, nameTap.takeIf { nameClickable }, channel) }
    // Stable wrapper, so a new callback instance does not rebuild the inline content.
    val currentEmoteClick by rememberUpdatedState(onEmoteClick)
    val emoteClick = remember { { seg: Segment.EmoteSeg -> currentEmoteClick?.invoke(seg); Unit } }
    val measured = pendingAspects(built)
    val inlineContent = remember(built, imageLoader, measured, style.smallEmotes, style.emoteFrameRate) {
        built.inline.mapValues { (_, data) ->
            inlineFor(data, imageLoader, emoteClick.takeIf { onEmoteClick != null }, style.smallEmotes, style.emoteFrameRate)
        }
    }

    val firstMessage = item.isFirstMessage && style.firstMessageBackground != null
    val background = when {
        // A rule's own color beats the mention color.
        item.highlight != null -> Color(item.highlight).copy(alpha = HIGHLIGHT_ALPHA)
        item.isMention -> style.mentionBackground
        firstMessage -> style.firstMessageBackground
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
            // Modulated per draw instead of Modifier.alpha, which renders each dimmed row into an
            // offscreen layer: tens of thousands of saveLayers while scrolling dimmed history. Text
            // and emotes never overlap, so the result looks the same.
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
            // The quote is inserted where the string puts it, whatever order a language uses.
            val template = stringResource(R.string.reply_to, style.nameOf(reply.parentLogin, reply.parentDisplayName), QUOTE_MARK)
            val quote = remember(template, item, style) { buildQuote(template, item.quote, reply.parentBody, style) }
            val quoteMeasured = pendingAspects(quote)
            val quoteContent = remember(quote, imageLoader, quoteMeasured, style.smallEmotes, style.emoteFrameRate) {
                quote.inline.mapValues { (_, data) ->
                    inlineFor(data, imageLoader, null, style.smallEmotes, style.emoteFrameRate, QUOTE_EMOTE_EM)
                }
            }
            Text(
                text = quote.text,
                inlineContent = quoteContent,
                color = style.secondaryText,
                fontSize = (style.fontSize - 2).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Its own tap target, so opening the conversation is not the same tap as answering.
                modifier = if (onGesture == null) Modifier else Modifier
                    .fillMaxWidth()
                    .clickable { onGesture(item, MessageGesture.Thread) },
            )
        }
        if (item.kind == MessageKind.UserNotice && item.systemText != null) {
            // The channel goes in front of the header, since subs and raids often have no message
            // of their own.
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
            // Side by side while they fit; two pictures in a message usually belong together.
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
 * A linked image shown in place of its URL. Tapping opens the link, which is also the fallback when
 * the image fails to load. [alone]: the only picture in the message, which may use more width.
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
        append(LinkText.display(url, style.shortLinks))
    }
}

private fun inlineFor(
    data: InlineData,
    loader: ImageLoader,
    onEmoteClick: ((Segment.EmoteSeg) -> Unit)?,
    smallEmotes: Boolean = false,
    frameRate: Float = EmoteFrameRate.ACTIVE,
    emoteEm: Float = EMOTE_EM,
): InlineTextContent = when (data) {
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
        // Wide zero-width overlays should not be clipped, so the widest aspect ratio is used.
        val aspect = (data.seg.overlays + base).maxOf { EmoteSizes.aspectRatio(it) }
        InlineTextContent(
            Placeholder((emoteEm * aspect).em, emoteEm.em, PlaceholderVerticalAlign.Center),
        ) {
            Box(Modifier.fillMaxSize().preferredFrameRate(frameRate).then(if (onEmoteClick != null) Modifier.clickable { onEmoteClick(data.seg) } else Modifier)) {
                (listOf(base) + data.seg.overlays).forEach { e ->
                    SharedEmoteImage(
                        url = if (smallEmotes) e.smallUrl else e.url,
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

/** Placeholder id of the channel picture; see [appendChannel]. */
private const val CHANNEL_ID = "c"

/** The picture of the message's channel, at the start of the line. */
private fun AnnotatedString.Builder.appendChannel(channel: ChannelMark) {
    appendInlineContent(CHANNEL_ID, channel.name)
    append(' ')
}

private fun buildLine(item: ChatItem, style: ChatStyle, onName: LinkInteractionListener?, channel: ChannelMark?): BuiltLine {
    val inline = HashMap<String, InlineData>()
    // Subs and raids have the channel in front of their header instead.
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
            // Styled like any name: every name can be tapped, so marking them would only add noise.
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

/**
 * The line above a reply: [template] is the localized "Replying to @name: …" with [QUOTE_MARK]
 * where the answered message goes, given as [quote] segments or as plain [fallback] text. Links are
 * always shortened and not clickable: the whole line opens the conversation and has room for one
 * line only.
 */
private fun buildQuote(template: String, quote: List<Segment>, fallback: String, style: ChatStyle): BuiltLine {
    val inline = HashMap<String, InlineData>()
    val at = template.indexOf(QUOTE_MARK)
    val text = buildAnnotatedString {
        if (at < 0) {
            append(template)
            return@buildAnnotatedString
        }
        append(template, 0, at)
        if (quote.isEmpty()) append(fallback)
        else quote.forEachIndexed { i, seg ->
            when (seg) {
                is Segment.Text -> append(seg.text)
                is Segment.EmoteSeg -> {
                    val id = "q$i"
                    inline[id] = InlineData.EmoteData(seg)
                    appendInlineContent(id, seg.emote.name)
                }
                is Segment.Link -> withStyle(SpanStyle(color = style.linkColor)) { append(LinkText.display(seg.text, short = true)) }
                is Segment.Mention -> append(seg.name)
            }
        }
        append(template, at + QUOTE_MARK.length, template.length)
    }
    return BuiltLine(text, inline)
}

/** Placeholder for the answered message in the reply template; never part of a message. */
private const val QUOTE_MARK = "\uE000"

/** Smaller emotes in the reply line, so it keeps its line height. */
private const val QUOTE_EMOTE_EM = 1.5f

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
            ) { append(LinkText.display(seg.text, style.shortLinks)) }
            is Segment.Mention -> {
                val color = seg.login?.let { readableNameColor(seg.color, it, style.dark, style.nameColors) } ?: Color.Unspecified
                withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(seg.name) }
            }
        }
    }
}

/** The nickname for [login], or [fallback]. */
fun ChatStyle.nameOf(login: String?, fallback: String): String =
    login?.let { nicknames[it.lowercase()] } ?: fallback

/**
 * "Name" or "Name (login)" for localized display names like Japanese or Korean ones. A nickname
 * replaces both.
 */
private fun displayName(item: ChatItem, style: ChatStyle): String {
    val display = item.displayName ?: item.login ?: ""
    val login = item.login ?: return display
    style.nicknames[login.lowercase()]?.let { return it }
    return if (display.equals(login, ignoreCase = true)) display else "$display ($login)"
}
