package me.lesh.chatter.ui.channels

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import me.lesh.chatter.R
import me.lesh.chatter.badges.Badge
import me.lesh.chatter.channels.ChannelGroup
import me.lesh.chatter.channels.ChannelInfo
import me.lesh.chatter.channels.displayName
import me.lesh.chatter.chat.RoomState
import me.lesh.chatter.irc.ConnectionState

/** One piece of the status line. [key] stays while only the text changes, e.g. slow mode from 30s to 60s. */
internal data class StatusPart(val key: String, val text: String)

/** The line under a channel's name. [roleBadge] is the user's role, shown before the parts. */
internal data class StatusLine(val parts: List<StatusPart>, val roleBadge: Badge? = null)

/**
 * A channel's status line: the user's role as its badge, a running Shared Chat and the chat modes;
 * the pill over the chat tells the connection. The tab bar shows the role in its tab instead and
 * passes no badge. Null when there is nothing to say.
 */
@Composable
internal fun channelStatus(
    connection: ConnectionState,
    state: RoomState?,
    roleBadge: Badge?,
    sharedWith: List<String>?,
): StatusLine? {
    // A dropped connection makes role and modes stale.
    if (connection != ConnectionState.Connected) return null
    val parts = buildList {
        // First, because it changes whose messages fill the chat.
        if (sharedWith != null) add(
            StatusPart(
                "shared",
                if (sharedWith.isEmpty()) stringResource(R.string.shared_chat)
                else stringResource(R.string.shared_chat_with, sharedWith.joinToString(", ")),
            ),
        )
        if (state == null) return@buildList
        if (state.slow > 0) add(StatusPart("slow", stringResource(R.string.mode_slow, state.slow)))
        if (state.followersOnly >= 0) add(
            StatusPart(
                "followers",
                if (state.followersOnly == 0) stringResource(R.string.mode_followers)
                else stringResource(R.string.mode_followers_time, formatMinutes(state.followersOnly)),
            ),
        )
        if (state.subsOnly) add(StatusPart("subs", stringResource(R.string.mode_subs)))
        if (state.emoteOnly) add(StatusPart("emotes", stringResource(R.string.mode_emotes)))
        if (state.uniqueChat) add(StatusPart("unique", stringResource(R.string.mode_unique)))
    }
    if (parts.isEmpty() && roleBadge == null) return null
    return StatusLine(parts, roleBadge)
}

/** A combined chat's status line: its channels, when it has a custom name. */
internal fun groupStatus(group: ChannelGroup, info: Map<String, ChannelInfo>): StatusLine? {
    if (group.name.isBlank()) return null
    return StatusLine(listOf(StatusPart("channels", group.channels.joinToString(" · ") { info[it]?.displayName ?: it })))
}

/**
 * Shows [line] with Material's fade through: the text fades out before the line folds away, and
 * fades in once it has opened. Each part comes and goes on its own, so the others stay still.
 * [padding] goes inside, so it folds away with the line.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StatusLineView(
    line: StatusLine?,
    imageLoader: ImageLoader,
    padding: PaddingValues = PaddingValues(0.dp),
) {
    val motion = MaterialTheme.motionScheme
    // The line folds away with the text it showed, not empty.
    var shown by remember { mutableStateOf(line) }
    if (line != null) shown = line
    AnimatedVisibility(
        visible = line != null,
        enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(fadeInSpec),
        exit = fadeOut(fadeOutSpec) + shrinkVertically(closeSpec()),
    ) {
        shown?.let { StatusParts(it, imageLoader, motion, Modifier.padding(padding)) }
    }
}

@Composable
private fun StatusParts(line: StatusLine, imageLoader: ImageLoader, motion: MotionScheme, modifier: Modifier) {
    val parts = remember { PartsOnScreen() }.update(line.parts)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        // Role first, then the parts, styled as a subtitle rather than chips so they do not compete
        // with the name.
        if (line.roleBadge != null) {
            AsyncImage(
                model = line.roleBadge.url,
                contentDescription = line.roleBadge.title,
                imageLoader = imageLoader,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        parts.forEachIndexed { index, (part, visible) ->
            // The dot after a part goes with it, so a part that leaves takes its dot along, and
            // the last part shown has none.
            val dotAfter = parts.drop(index + 1).any { it.second.targetState }
            AnimatedVisibility(visibleState = visible, enter = partEnter(motion), exit = partExit()) {
                Row {
                    AnimatedContent(
                        targetState = part.text,
                        transitionSpec = { fadeThrough() using SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() } },
                        label = "status-part",
                    ) { PartText(it) }
                    AnimatedVisibility(visible = dotAfter, enter = partEnter(motion), exit = partExit()) { PartText(" · ") }
                }
            }
        }
    }
}

@Composable
private fun PartText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The parts on screen, with the ones that left kept in place until they have faded out. Parts
 * there when the line appears show at once, since the whole line fades in.
 */
private class PartsOnScreen {
    private var order = emptyList<String>()
    private val texts = mutableMapOf<String, String>()
    private val visible = mutableMapOf<String, MutableTransitionState<Boolean>>()
    private var first = true

    fun update(parts: List<StatusPart>): List<Pair<StatusPart, MutableTransitionState<Boolean>>> {
        val keys = parts.map { it.key }
        val gone = order.filter { it !in keys && visible[it]?.let { s -> s.isIdle && !s.currentState } != false }
        texts -= gone.toSet()
        visible -= gone.toSet()
        order = keepLeaving(order - gone.toSet(), keys)
        for (part in parts) {
            texts[part.key] = part.text
            visible.getOrPut(part.key) { MutableTransitionState(first) }.targetState = true
        }
        for (key in order) if (key !in keys) visible.getValue(key).targetState = false
        first = false
        return order.map { StatusPart(it, texts.getValue(it)) to visible.getValue(it) }
    }
}

/**
 * [new] with the keys of [old] that are not in it any more put back after the key they followed,
 * so a part fading out stays where it was.
 */
internal fun keepLeaving(old: List<String>, new: List<String>): List<String> {
    val result = new.toMutableList()
    old.forEachIndexed { index, key ->
        if (key in result) return@forEachIndexed
        val before = old.take(index).lastOrNull { it in result }
        result.add(if (before == null) 0 else result.indexOf(before) + 1, key)
    }
    return result
}

// Material's fade through: the old content fades out, then the new one fades in, never both at
// once. Tweens with Material's timings, since springs cannot wait for each other.
private const val FADE_OUT_MILLIS = 90
private const val FADE_IN_MILLIS = 210
private val fadeOutSpec = tween<Float>(FADE_OUT_MILLIS, easing = FastOutLinearInEasing)
private val fadeInSpec = tween<Float>(FADE_IN_MILLIS, delayMillis = FADE_OUT_MILLIS, easing = LinearOutSlowInEasing)

/** Closes the gap once the text has faded out. */
private fun <T> closeSpec() = tween<T>(FADE_IN_MILLIS, delayMillis = FADE_OUT_MILLIS, easing = FastOutSlowInEasing)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun partEnter(motion: MotionScheme): EnterTransition =
    expandHorizontally(motion.defaultSpatialSpec(), expandFrom = Alignment.Start) + fadeIn(fadeInSpec)

private fun partExit(): ExitTransition = fadeOut(fadeOutSpec) + shrinkHorizontally(closeSpec(), shrinkTowards = Alignment.Start)

private fun fadeThrough(): ContentTransform =
    (fadeIn(fadeInSpec) + scaleIn(fadeInSpec, initialScale = 0.92f, transformOrigin = TransformOrigin(0f, 0.5f))) togetherWith
        fadeOut(fadeOutSpec)

internal fun formatMinutes(minutes: Int): String = when {
    minutes % 10_080 == 0 -> "${minutes / 10_080}w"
    minutes % 1440 == 0 -> "${minutes / 1440}d"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes}m"
}
