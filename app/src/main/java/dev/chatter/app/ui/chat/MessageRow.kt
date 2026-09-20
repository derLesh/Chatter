package dev.chatter.app.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
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
    /** Keep deleted messages visible (struck through) instead of dropping them from the list. */
    val showDeleted: Boolean,
    /** How name colors are adjusted for readability. */
    val nameColors: NameColorPalette,
    /** Names the user gave chatters, by lowercase login. Usually empty. */
    val nicknames: Map<String, String>,
)

private const val BADGE_EM = 1.35f
/** How strongly a rule's highlight color tints the message background. */
private const val HIGHLIGHT_ALPHA = 0.2f
/** Messages loaded from history are clearly dimmed so live chat stands out. */
private const val HISTORICAL_ALPHA = 0.5f
private const val EMOTE_EM = 2.1f

private class BuiltLine(val text: AnnotatedString, val inline: Map<String, InlineData>)

private sealed interface InlineData {
    data class BadgeData(val badge: Badge) : InlineData
    data class EmoteData(val seg: Segment.EmoteSeg) : InlineData
}

// One formatter per pattern and thread: they are not safe to share across threads.
private val timeFormats = object : ThreadLocal<MutableMap<String, SimpleDateFormat>>() {
    override fun initialValue() = mutableMapOf<String, SimpleDateFormat>()
}

private fun formatTime(pattern: String, at: Long): String =
    timeFormats.get()!!.getOrPut(pattern) { SimpleDateFormat(pattern, Locale.getDefault()) }.format(Date(at))

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageRow(
    item: ChatItem,
    style: ChatStyle,
    imageLoader: ImageLoader,
    onAction: (ChatItem) -> Unit,
    onEmoteClick: ((Segment.EmoteSeg) -> Unit)? = null,
) {
    val built = remember(item, style) { buildLine(item, style) }
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
            .combinedClickable(onClick = { onAction(item) }, onLongClick = { onAction(item) })
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .alpha(
                when {
                    item.deleted -> 0.4f
                    item.historical -> HISTORICAL_ALPHA
                    else -> 1f
                },
            ),
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
            Text(
                text = item.systemText,
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
    }
}

private fun inlineFor(data: InlineData, loader: ImageLoader, onEmoteClick: ((Segment.EmoteSeg) -> Unit)?): InlineTextContent = when (data) {
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
            Box(Modifier.fillMaxSize().then(if (onEmoteClick != null) Modifier.clickable { onEmoteClick(data.seg) } else Modifier)) {
                (listOf(base) + data.seg.overlays).forEach { e ->
                    AsyncImage(
                        model = e.url,
                        contentDescription = e.name,
                        imageLoader = loader,
                        contentScale = ContentScale.Fit,
                        onSuccess = EmoteSizes.onLoaded(e),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

private fun buildLine(item: ChatItem, style: ChatStyle): BuiltLine {
    val inline = HashMap<String, InlineData>()
    if (item.kind == MessageKind.Notice) {
        val text = buildAnnotatedString {
            withStyle(SpanStyle(color = style.secondaryText, fontStyle = FontStyle.Italic)) {
                style.timestamps.pattern?.let { append(formatTime(it, item.timestamp) + " ") }
                append(item.systemText ?: item.text)
                if (item.segments.isNotEmpty()) {
                    append(' ')
                    appendSegments(item.segments, inline, style)
                }
            }
        }
        return BuiltLine(text, inline)
    }
    if (item.kind == MessageKind.UserNotice && item.segments.isEmpty()) return BuiltLine(AnnotatedString(""), inline)

    val nameColor = readableNameColor(item.color, item.login, style.dark, style.nameColors)
    val isAction = item.kind == MessageKind.Action
    val text = buildAnnotatedString {
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
            append(displayName(item, style))
        }
        append(if (isAction) " " else ": ")

        val body = SpanStyle(
            color = if (isAction) nameColor else Color.Unspecified,
            fontStyle = if (isAction) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (item.deleted) TextDecoration.LineThrough else null,
        )
        withStyle(body) { appendSegments(item.segments, inline, style) }
    }
    return BuiltLine(text, inline)
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
