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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import dev.chatter.app.R
import dev.chatter.app.badges.NamePaint
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.LinkText
import dev.chatter.app.chat.MessageKind
import dev.chatter.app.chat.PowerUp
import dev.chatter.app.chat.Segment
import dev.chatter.app.settings.SpecialMessage

private const val BADGE_EM = 1.35f
/** How strongly a rule's highlight color tints the background. */
private const val HIGHLIGHT_ALPHA = 0.2f
/**
 * History is dimmed so live chat stands out. Half leaves text on black at about 5:1, but on white
 * at about 3:1, so the light theme keeps more to look as dimmed and stay as readable.
 */
private const val HISTORICAL_ALPHA = 0.5f
private const val HISTORICAL_ALPHA_LIGHT = 0.65f
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
        item.highlight == ChatItem.MENTION_HIGHLIGHT -> style.mentionBackground
        // A rule's own color beats the mention color.
        item.highlight != null -> Color(item.highlight).copy(alpha = HIGHLIGHT_ALPHA)
        item.isMention -> style.mentionBackground
        item.powerUp == PowerUp.Highlighted && SpecialMessage.Highlighted in style.specialMessages -> style.highlightedBackground
        firstMessage -> style.firstMessageBackground
        // Cheers stand out a little, like subs.
        item.kind == MessageKind.UserNotice || item.bits > 0 -> style.noticeBackground
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
                    item.historical -> if (style.dark) HISTORICAL_ALPHA else HISTORICAL_ALPHA_LIGHT
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
        if (item.rewardId != null && SpecialMessage.Reward in style.specialMessages) {
            Text(
                text = style.rewardNames[item.rewardId]?.let { stringResource(R.string.reward_redeemed_named, it) }
                    ?: stringResource(R.string.reward_redeemed),
                color = style.accent,
                fontSize = (style.fontSize - 2).sp,
                fontWeight = FontWeight.Medium,
            )
        }
        if (item.powerUp == PowerUp.Effect && SpecialMessage.Effect in style.specialMessages) {
            Text(
                text = stringResource(R.string.message_effect),
                color = style.secondaryText,
                fontSize = (style.fontSize - 2).sp,
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
        val header = item.watchStreak?.let { streams ->
            val name = style.nameOf(item.login, item.displayName ?: item.login.orEmpty())
            pluralStringResource(R.plurals.watch_streak, streams, name, streams)
        } ?: item.systemText
        if (item.kind == MessageKind.UserNotice && header != null) {
            // The channel goes in front of the header, since subs and raids often have no message
            // of their own.
            Text(
                text = remember(header, channel) {
                    buildAnnotatedString {
                        if (channel != null) appendChannel(channel)
                        append(header)
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
            val paint = item.paint?.takeIf { style.paints && built.nameRange != null }
            if (paint == null) {
                Text(
                    text = built.text,
                    inlineContent = inlineContent,
                    fontSize = style.fontSize.sp,
                    lineHeight = (style.fontSize * 1.45f).sp,
                )
            } else {
                PaintedLine(built, paint, inlineContent, style, imageLoader)
            }
        }
        if (built.images.isNotEmpty() || built.gifs.isNotEmpty()) {
            // Side by side while they fit; two pictures in a message usually belong together.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(IMAGE_GAP),
                verticalArrangement = Arrangement.spacedBy(IMAGE_GAP),
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            ) {
                val alone = built.images.size + built.gifs.size == 1
                built.images.forEach { url -> LinkedImage(url, style, imageLoader, alone) }
                built.gifs.forEach { gif -> LinkedImage(gif.url, style, imageLoader, alone, title = gif.title) }
            }
        }
        style.postCard?.let { card -> built.posts.forEach { PostCard(it, card, style, imageLoader) } }
    }
}

/**
 * A line whose name has a 7TV paint. The paint is placed once the line is laid out and the name's
 * bounds are known; see [namePaintStyle].
 */
@Composable
private fun PaintedLine(
    built: BuiltLine,
    paint: NamePaint,
    inlineContent: Map<String, InlineTextContent>,
    style: ChatStyle,
    imageLoader: ImageLoader,
) {
    val range = built.nameRange ?: return
    var nameBounds by remember(built) { mutableStateOf<Rect?>(null) }
    val image = paint.imageUrl?.let { rememberPaintImage(it, imageLoader) }
    val density = LocalDensity.current
    val text = remember(built, paint, nameBounds, image, density) {
        AnnotatedString.Builder(built.text).apply {
            addStyle(namePaintStyle(paint, nameBounds, image, density), range.first, range.last + 1)
        }.toAnnotatedString()
    }
    Text(
        text = text,
        inlineContent = inlineContent,
        fontSize = style.fontSize.sp,
        lineHeight = (style.fontSize * 1.45f).sp,
        onTextLayout = { layout ->
            val bounds = layout.boundsOf(range)
            if (bounds != nameBounds) nameBounds = bounds
        },
    )
}

/**
 * A linked image or GIF shown in place of its URL. Tapping opens the link, which is also the
 * fallback when the image fails to load, written as [title] if there is one. [alone]: the only
 * picture in the message, which may use more width.
 */
@Composable
private fun LinkedImage(url: String, style: ChatStyle, loader: ImageLoader, alone: Boolean, title: String? = null) {
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) {
        Text(
            text = remember(url, title, style.linkColor) { linkText(url, style, title) },
            fontSize = style.fontSize.sp,
            lineHeight = (style.fontSize * 1.45f).sp,
        )
        return
    }
    val uriHandler = LocalUriHandler.current
    AsyncImage(
        model = url,
        contentDescription = title?.removeSurrounding("[", "]") ?: stringResource(R.string.linked_image),
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

/** [url] as a link in the chat, for a picture or post that could not be shown. */
internal fun linkText(url: String, style: ChatStyle, title: String? = null) = buildAnnotatedString {
    withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = style.linkColor, textDecoration = TextDecoration.Underline)))) {
        append(title ?: LinkText.display(url, style.shortLinks))
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
    is InlineData.CheerData -> InlineTextContent(
        Placeholder(emoteEm.em, emoteEm.em, PlaceholderVerticalAlign.Center),
    ) {
        SharedEmoteImage(
            url = data.picture.url,
            contentDescription = data.picture.name,
            loader = loader,
            onLoaded = EmoteSizes.onSize(data.picture),
            modifier = Modifier.fillMaxSize().preferredFrameRate(frameRate),
        )
    }
    is InlineData.EmoteData -> {
        val base = data.seg.emote
        // Wide zero-width overlays should not be clipped, so the widest aspect ratio is used.
        val aspect = (data.seg.overlays + base).maxOf { EmoteSizes.aspectRatio(it) }
        val em = if (data.giant) emoteEm * GIANT_EMOTE_SCALE else emoteEm
        InlineTextContent(
            Placeholder((em * aspect).em, em.em, PlaceholderVerticalAlign.Center),
        ) {
            Box(Modifier.fillMaxSize().preferredFrameRate(frameRate).then(if (onEmoteClick != null) Modifier.clickable { onEmoteClick(data.seg) } else Modifier)) {
                (listOf(base) + data.seg.overlays).forEach { e ->
                    SharedEmoteImage(
                        url = when {
                            smallEmotes -> e.smallUrl
                            // The usual size would be blurry this large.
                            data.giant -> e.largeUrl
                            else -> e.url
                        },
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

/** About the size Twitch draws a giant emote at, next to its usual one. */
private const val GIANT_EMOTE_SCALE = 3f

/** Smaller emotes in the reply line, so it keeps its line height. */
private const val QUOTE_EMOTE_EM = 1.5f
