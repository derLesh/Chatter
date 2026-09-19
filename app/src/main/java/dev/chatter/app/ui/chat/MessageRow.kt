package dev.chatter.app.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
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
import dev.chatter.app.ui.theme.readableNameColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything a message row needs besides the message itself. Changes rarely. */
@Immutable
data class ChatStyle(
    val fontSize: Float,
    val showTimestamps: Boolean,
    val dark: Boolean,
    val secondaryText: Color,
    val linkColor: Color,
    val mentionBackground: Color,
    val noticeBackground: Color,
    val accent: Color,
)

private const val BADGE_EM = 1.35f
private const val EMOTE_EM = 2.1f

private class BuiltLine(val text: AnnotatedString, val inline: Map<String, InlineData>)

private sealed interface InlineData {
    data class BadgeData(val badge: Badge) : InlineData
    data class EmoteData(val seg: Segment.EmoteSeg) : InlineData
}

private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("HH:mm", Locale.getDefault())
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageRow(
    item: ChatItem,
    style: ChatStyle,
    imageLoader: ImageLoader,
    onAction: (ChatItem) -> Unit,
) {
    val built = remember(item, style) { buildLine(item, style) }
    val inlineContent = remember(built, imageLoader) {
        built.inline.mapValues { (_, data) -> inlineFor(data, imageLoader) }
    }

    val background = when {
        item.isMention -> style.mentionBackground
        item.kind == MessageKind.UserNotice -> style.noticeBackground
        else -> Color.Transparent
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(background)
            .combinedClickable(onClick = { onAction(item) }, onLongClick = { onAction(item) })
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .alpha(if (item.deleted) 0.45f else if (item.historical) 0.8f else 1f),
    ) {
        item.reply?.let { reply ->
            Text(
                text = stringResource(R.string.reply_to, reply.parentDisplayName, reply.parentBody),
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

private fun inlineFor(data: InlineData, loader: ImageLoader): InlineTextContent = when (data) {
    is InlineData.BadgeData -> InlineTextContent(
        Placeholder(BADGE_EM.em, BADGE_EM.em, PlaceholderVerticalAlign.Center),
    ) {
        AsyncImage(model = data.badge.url, contentDescription = data.badge.title, imageLoader = loader, modifier = Modifier.fillMaxSize())
    }
    is InlineData.EmoteData -> {
        val base = data.seg.emote
        // Wide zero-width overlays should not be clipped: use the widest aspect ratio.
        val aspect = (data.seg.overlays.map { it.aspectRatio } + base.aspectRatio).max()
        InlineTextContent(
            Placeholder((EMOTE_EM * aspect).em, EMOTE_EM.em, PlaceholderVerticalAlign.Center),
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(model = base.url, contentDescription = base.name, imageLoader = loader, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                data.seg.overlays.forEach { o ->
                    AsyncImage(model = o.url, contentDescription = o.name, imageLoader = loader, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
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
                if (style.showTimestamps) append(timeFormat.get()!!.format(Date(item.timestamp)) + " ")
                append(item.systemText ?: item.text)
            }
        }
        return BuiltLine(text, inline)
    }
    if (item.kind == MessageKind.UserNotice && item.segments.isEmpty()) return BuiltLine(AnnotatedString(""), inline)

    val nameColor = readableNameColor(item.color, item.login, style.dark)
    val isAction = item.kind == MessageKind.Action
    val text = buildAnnotatedString {
        if (style.showTimestamps) {
            withStyle(SpanStyle(color = style.secondaryText, fontSize = (style.fontSize - 2).sp)) {
                append(timeFormat.get()!!.format(Date(item.timestamp)))
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
            append(displayName(item))
        }
        append(if (isAction) " " else ": ")

        val body = SpanStyle(
            color = if (isAction) nameColor else Color.Unspecified,
            fontStyle = if (isAction) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (item.deleted) TextDecoration.LineThrough else null,
        )
        withStyle(body) {
            item.segments.forEachIndexed { i, seg ->
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
                    is Segment.Mention -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(seg.name) }
                }
            }
        }
    }
    return BuiltLine(text, inline)
}

/** "Name" or "Name (login)" for localized display names like Japanese or Korean ones. */
private fun displayName(item: ChatItem): String {
    val display = item.displayName ?: item.login ?: ""
    val login = item.login ?: return display
    return if (display.equals(login, ignoreCase = true)) display else "$display ($login)"
}
