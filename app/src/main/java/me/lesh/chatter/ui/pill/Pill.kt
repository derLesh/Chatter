package me.lesh.chatter.ui.pill

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.lesh.chatter.R

/**
 * What a pill tells, which picks its icon. Only the icon takes a color of its own: a pill sits
 * where the user writes, so even an error should not alarm.
 */
enum class PillTone {
    /** No icon, for a message that brings its own action, like Undo. */
    Plain,
    /** Under way: a loading indicator in place of the icon. */
    Busy,
    Info,
    Success,
    /** Something is off but the app carries on, e.g. 7TV does not answer. */
    Warning,
    /** Something the user asked for did not work. */
    Error,
    /** Waiting for the phone's network. */
    Offline,
    ;

    /** Vibrates like a refused tap when shown, with haptics on. */
    val isProblem: Boolean get() = this == Warning || this == Error || this == Offline
}

/**
 * One pill's text. [highlight]: where in [text] the name it is about stands, set in bold; see
 * [filled]. [spoken]: what a screen reader announces instead of [text] when that changes too often
 * to read out, like an upload's percentage.
 */
@Immutable
data class PillContent(
    val text: String,
    val tone: PillTone,
    val highlight: IntRange? = null,
    val spoken: String? = null,
) {
    companion object {
        /**
         * The text [format] makes with [fill] put in, and [fill] in bold. Formatted around a mark
         * first, so the bold lands on the name even where the sentence has the same word before it,
         * like a set named "to" in "Added to to".
         */
        fun filled(fill: String, tone: PillTone, format: (String) -> String): PillContent {
            val marked = format(FILL_MARK)
            val start = marked.indexOf(FILL_MARK)
            if (start < 0) return PillContent(marked, tone)
            return PillContent(marked.replaceFirst(FILL_MARK, fill), tone, start until start + fill.length)
        }
    }
}

/** A character from the private use area, which no string resource or name contains. */
private const val FILL_MARK = "\uE000"

/** At one line a full pill, and still round at the corners when the text wraps. */
private val PillShape = RoundedCornerShape(20.dp)
private val PillMinHeight = 40.dp
private val PillElevation = 3.dp
/** Wide enough for a sentence, narrow enough to read on a tablet. */
internal val PillMaxWidth = 560.dp

/**
 * The surface every pill and chip over the chat shares: its shape, its shadow and the secondary
 * container instead of Material's inverted snackbar, which glares white over a dark chat.
 */
@Composable
fun PillSurface(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = contentColorFor(color),
    shadowElevation: Dp = PillElevation,
    content: @Composable () -> Unit,
) {
    if (onClick == null) {
        Surface(modifier, PillShape, color, contentColor, shadowElevation = shadowElevation, content = content)
    } else {
        Surface(
            onClick, modifier, enabled, PillShape, color, contentColor,
            shadowElevation = shadowElevation, content = content,
        )
    }
}

/** A pill that says [content], with [action] (a text button) at its end. */
@Composable
fun Pill(content: PillContent, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    PillSurface(modifier.widthIn(max = PillMaxWidth)) {
        PillRow(
            icon = if (content.tone == PillTone.Plain) null else ({ PillIcon(content.tone) }),
            action = action,
        ) { PillText(content, it) }
    }
}

/** The text of every pill: a size smaller than body text, so a sentence fits on one line. */
@Composable
internal fun PillText(content: PillContent, modifier: Modifier = Modifier) {
    Text(
        highlighted(content.text, content.highlight),
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.padding(vertical = 10.dp),
    )
}

@Composable
internal fun PillRow(
    icon: (@Composable () -> Unit)?,
    action: (@Composable () -> Unit)?,
    /** Gets the modifier that lets it wrap in the space the icon and action leave. */
    text: @Composable (Modifier) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = PillMinHeight)
            .padding(start = if (icon == null) 16.dp else 10.dp, end = if (action == null) 16.dp else 4.dp),
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(8.dp))
        }
        text(Modifier.weight(1f, fill = false))
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            action()
        }
    }
}

/** [tone]'s icon, in a slot of the same size for each, so the text does not move when it changes. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PillIcon(tone: PillTone) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        val icon: Pair<ImageVector, Color>? = when (tone) {
            PillTone.Plain, PillTone.Busy -> null
            PillTone.Info -> Icons.Filled.Info to colors.primary
            PillTone.Success -> Icons.Filled.CheckCircle to colors.primary
            PillTone.Warning -> Icons.Filled.Warning to colors.error
            PillTone.Error -> ImageVector.vectorResource(R.drawable.ic_error) to colors.error
            // In the pill's own color: a missing network is nobody's fault.
            PillTone.Offline -> ImageVector.vectorResource(R.drawable.ic_cloud_off) to LocalContentColor.current
        }
        if (tone == PillTone.Busy) LoadingIndicator(Modifier.size(24.dp), color = LocalContentColor.current)
        icon?.let { (vector, tint) -> Icon(vector, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) }
    }
}

internal fun highlighted(text: String, highlight: IntRange?): AnnotatedString {
    if (highlight == null || highlight.isEmpty() || highlight.first < 0 || highlight.last >= text.length) {
        return AnnotatedString(text)
    }
    return buildAnnotatedString {
        append(text)
        addStyle(SpanStyle(fontWeight = FontWeight.Bold), highlight.first, highlight.last + 1)
    }
}
