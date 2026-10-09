package dev.chatter.app.ui.chat

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import dev.chatter.app.badges.Badge
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.ImageLinks
import dev.chatter.app.chat.LinkText
import dev.chatter.app.chat.MessageKind
import dev.chatter.app.chat.Segment
import dev.chatter.app.emotes.Emote
import dev.chatter.app.ui.theme.readableNameColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A message as styled text, built once per message and style: timestamp, badges, name and body,
 * with placeholders for the pictures in [inline]. [MessageRow] draws it.
 */
internal class BuiltLine(
    val text: AnnotatedString,
    val inline: Map<String, InlineData>,
    /** URLs of images taken out of the line and drawn below it. */
    val images: List<String> = emptyList(),
    /** Where the sender's name is in [text], for drawing a paint over it. */
    val nameRange: IntRange? = null,
)

/**
 * Aspect ratios of emotes in [line] whose size is not known yet. Reading them while composing
 * relayouts the row once a BTTV emote's size is known.
 */
internal fun pendingAspects(line: BuiltLine): List<Float> = line.inline.values.mapNotNull { data ->
    (data as? InlineData.EmoteData)?.seg?.takeIf { s -> !s.emote.sizeKnown || s.overlays.any { !it.sizeKnown } }
        ?.let { s -> (s.overlays + s.emote).maxOf { EmoteSizes.aspectRatio(it) } }
}

/** What a placeholder in a [BuiltLine] stands for. */
internal sealed interface InlineData {
    data class ChannelData(val mark: ChannelMark) : InlineData
    data class BadgeData(val badge: Badge) : InlineData
    data class EmoteData(val seg: Segment.EmoteSeg) : InlineData
    /** Not clickable: the emote card would offer to type "Cheer100" into the input. */
    data class CheerData(val picture: Emote) : InlineData
}

// SimpleDateFormat is not thread-safe: one per pattern and thread.
private val timeFormats = object : ThreadLocal<MutableMap<String, SimpleDateFormat>>() {
    override fun initialValue() = mutableMapOf<String, SimpleDateFormat>()
}

private fun formatTime(pattern: String, at: Long): String =
    timeFormats.get()!!.getOrPut(pattern) { SimpleDateFormat(pattern, Locale.getDefault()) }.format(Date(at))

/** Placeholder id of the channel picture; see [appendChannel]. */
internal const val CHANNEL_ID = "c"

/** The picture of the message's channel, at the start of the line. */
internal fun AnnotatedString.Builder.appendChannel(channel: ChannelMark) {
    appendInlineContent(CHANNEL_ID, channel.name)
    append(' ')
}

internal fun buildLine(item: ChatItem, style: ChatStyle, onName: LinkInteractionListener?, channel: ChannelMark?): BuiltLine {
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
    var nameStart = 0
    var nameEnd = 0
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
        nameStart = length
        withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.Bold)) {
            if (onName == null) append(displayName(item, style))
            // Styled like any name: every name can be tapped, so marking them would only add noise.
            else withLink(LinkAnnotation.Clickable("name", TextLinkStyles(SpanStyle()), onName)) {
                append(displayName(item, style))
            }
        }
        nameEnd = length
        append(if (isAction) " " else ": ")

        val body = SpanStyle(
            color = if (isAction) nameColor else Color.Unspecified,
            fontStyle = if (isAction) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (item.deleted) TextDecoration.LineThrough else null,
        )
        withStyle(body) { appendSegments(segments, inline, style) }
    }
    return BuiltLine(text, inline, images, (nameStart until nameEnd).takeIf { !it.isEmpty() })
}

/**
 * The line above a reply: [template] is the localized "Replying to @name: …" with [QUOTE_MARK]
 * where the answered message goes, given as [quote] segments or as plain [fallback] text. Links are
 * always shortened and not clickable: the whole line opens the conversation and has room for one
 * line only.
 */
internal fun buildQuote(template: String, quote: List<Segment>, fallback: String, style: ChatStyle): BuiltLine {
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
                is Segment.Cheer -> appendCheer(seg, "q$i", inline, style)
            }
        }
        append(template, at + QUOTE_MARK.length, template.length)
    }
    return BuiltLine(text, inline)
}

/** Placeholder for the answered message in the reply template; never part of a message. */
internal const val QUOTE_MARK = "\uE000"

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
            is Segment.Cheer -> appendCheer(seg, "e$i", inline, style)
        }
    }
}

/** The cheermote in the theme's variant, then the amount in its tier's color. */
private fun AnnotatedString.Builder.appendCheer(seg: Segment.Cheer, id: String, inline: MutableMap<String, InlineData>, style: ChatStyle) {
    val picture = if (style.dark) seg.dark else seg.light
    inline[id] = InlineData.CheerData(picture)
    appendInlineContent(id, picture.name)
    withStyle(SpanStyle(color = Color(seg.color), fontWeight = FontWeight.Bold)) { append(seg.amount.toString()) }
}

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
