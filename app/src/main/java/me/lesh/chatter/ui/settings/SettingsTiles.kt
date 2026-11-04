package me.lesh.chatter.ui.settings

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import me.lesh.chatter.R

// The tiles all settings pages are built from, shared because the pages span several files.

internal class GroupScope {
    val items = mutableListOf<Pair<Int?, @Composable () -> Unit>>()

    /** One tile. [key] is the title a search result refers to; see [LocalSettingsTarget]. */
    fun item(key: Int? = null, content: @Composable () -> Unit) {
        items += key to content
    }
}

/**
 * The setting a search result led to, by title: its page scrolls to it and highlights it briefly.
 * Groups are found by their heading, tiles by their key.
 */
internal val LocalSettingsTarget = compositionLocalOf<Int?> { null }

/** Most of the way through a revealed tile opening out, so the scroll carries on from it. */
private const val REVEALED_TILE_DELAY_MS = 180L

/** Long enough for the page to finish sliding in; scrolling during the slide would miss. */
private const val REVEAL_DELAY_MS = 350L

/**
 * Related settings as separate tiles with small gaps (Android 16 style): strong outer corners,
 * slight ones between tiles. A held tile rounds all its corners; a tile that shows up later, such
 * as one a switch above reveals, opens out instead of appearing at once.
 */
@Composable
internal fun SettingsGroup(title: Int? = null, build: GroupScope.() -> Unit) {
    val items = GroupScope().apply(build).items.mapIndexed { i, (itemKey, content) -> TileEntry(itemKey ?: i, itemKey, content) }
    val target = LocalSettingsTarget.current
    val (groupReveal, groupGlow) = reveal(title != null && title == target)
    // False only while the group is composed for the first time.
    val settled = remember { booleanArrayOf(false) }
    SideEffect { settled[0] = true }
    // Tiles that just left stay until they have folded away.
    val memory = remember { TileMemory() }
    var foldedAway by remember { mutableIntStateOf(0) }
    val shown = remember(items, foldedAway) { withLeaving(memory.shown, items) }
    SideEffect { memory.shown = shown }
    val present = items.mapTo(HashSet()) { it.id }
    EnterInOrder(groupReveal) {
        Column {
            if (title != null) {
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Corners follow the tiles that stay, so the tile above a leaving one rounds off
                // while it folds away.
                val staying = shown.filter { it.id in present }
                shown.forEach { entry ->
                    key(entry.id) {
                        val visible = remember { MutableTransitionState(settled[0].not()) }
                        visible.targetState = entry.id in present
                        if (visible.isIdle && !visible.currentState && !visible.targetState) {
                            LaunchedEffect(Unit) {
                                memory.shown = memory.shown.filter { it.id != entry.id }
                                foldedAway++
                            }
                        }
                        // A tile a switch revealed is scrolled into view once it has opened out.
                        val requester = remember { BringIntoViewRequester() }
                        val cameLater = remember { settled[0] }
                        if (cameLater) {
                            LaunchedEffect(Unit) {
                                delay(REVEALED_TILE_DELAY_MS)
                                requester.bringIntoView()
                            }
                        }
                        val index = staying.indexOf(entry).takeIf { it >= 0 } ?: shown.indexOf(entry)
                        val count = if (entry.id in present) staying.size else shown.size
                        AnimatedVisibility(
                            visibleState = visible,
                            enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec(), expandFrom = Alignment.Top) +
                                fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                            exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec(), shrinkTowards = Alignment.Top) +
                                fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
                            modifier = Modifier.bringIntoViewRequester(requester),
                        ) {
                            Tile(
                                index, count, groupGlow,
                                revealed = entry.key != null && entry.key == target,
                                content = entry.content,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One tile of a group; [id] is its [key], or its place when it has none. */
private class TileEntry(val id: Any, val key: Int?, val content: @Composable () -> Unit)

private class TileMemory {
    var shown: List<TileEntry> = emptyList()
}

/** [now], with the tiles of [before] that are gone kept after the tile they followed. */
private fun withLeaving(before: List<TileEntry>, now: List<TileEntry>): List<TileEntry> {
    val nowIds = now.mapTo(HashSet()) { it.id }
    val result = now.toMutableList()
    before.forEachIndexed { i, entry ->
        if (entry.id in nowIds) return@forEachIndexed
        val after = before.subList(0, i).lastOrNull { prev -> result.any { it.id == prev.id } }
        val at = if (after == null) 0 else result.indexOfFirst { it.id == after.id } + 1
        result.add(at, entry)
    }
    return result
}

@Composable
private fun Tile(index: Int, count: Int, groupGlow: Float, revealed: Boolean, content: @Composable () -> Unit) {
    val (tileReveal, tileGlow) = reveal(revealed)
    val presses = remember { MutableInteractionSource() }
    val pressed by presses.collectIsPressedAsState()
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val held by animateDpAsState(if (pressed) OUTER_CORNER else 0.dp, spatial, label = "tile-press")
    val top by animateDpAsState(if (index == 0) OUTER_CORNER else INNER_CORNER, spatial, label = "tile-top")
    val bottom by animateDpAsState(if (index == count - 1) OUTER_CORNER else INNER_CORNER, spatial, label = "tile-bottom")
    val topCorner = maxOf(top, held).coerceAtLeast(0.dp)
    val bottomCorner = maxOf(bottom, held).coerceAtLeast(0.dp)
    Surface(
        color = lerp(
            MaterialTheme.colorScheme.surfaceContainer,
            MaterialTheme.colorScheme.primaryContainer,
            maxOf(groupGlow, tileGlow),
        ),
        shape = RoundedCornerShape(topStart = topCorner, topEnd = topCorner, bottomStart = bottomCorner, bottomEnd = bottomCorner),
        modifier = Modifier.fillMaxWidth().then(tileReveal),
    ) {
        CompositionLocalProvider(LocalTilePresses provides presses) {
            Box(Modifier.padding(vertical = 2.dp)) { content() }
        }
    }
}

private val OUTER_CORNER = 24.dp
private val INNER_CORNER = 4.dp

/**
 * Scrolls to the element once when [active] and highlights it: returns the modifier and the current
 * glow from 1 fading to 0.
 */
@Composable
private fun reveal(active: Boolean): Pair<Modifier, Float> {
    if (!active) return Modifier to 0f
    val requester = remember { BringIntoViewRequester() }
    val glow = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(REVEAL_DELAY_MS)
        requester.bringIntoView()
        glow.snapTo(1f)
        glow.animateTo(0f, tween(durationMillis = 1_600, delayMillis = 400))
    }
    return Modifier.bringIntoViewRequester(requester) to glow.value
}

/**
 * A category's own colour, so the settings home reads as a set of places rather than one list.
 * Tones 90 and 40 of a Material palette in light themes, 30 and 90 in dark ones.
 */
internal enum class CategoryHue(
    private val lightContainer: Long,
    private val lightIcon: Long,
    private val darkContainer: Long,
    private val darkIcon: Long,
) {
    Blue(0xFFD1E4FF, 0xFF0061A4, 0xFF00497D, 0xFFD1E4FF),
    Green(0xFFC3EFC0, 0xFF1B6C2A, 0xFF0C5220, 0xFFB4F1B5),
    Orange(0xFFFFDBC8, 0xFF964900, 0xFF723600, 0xFFFFDBC8),
    Pink(0xFFFFD8E7, 0xFFA0306A, 0xFF7F1D52, 0xFFFFD8E7),
    Purple(0xFFEBDDFF, 0xFF6B4EA8, 0xFF52378E, 0xFFEBDDFF),
    Teal(0xFFB6EEEB, 0xFF006A67, 0xFF00504E, 0xFFB6EEEB),
    Amber(0xFFFFE08D, 0xFF745B00, 0xFF574400, 0xFFFFE08D),
    Red(0xFFFFDAD6, 0xFFB3261E, 0xFF8C1D18, 0xFFFFDAD6),
    Indigo(0xFFDFE0FF, 0xFF4A5BAF, 0xFF314296, 0xFFDFE0FF),
    Sky(0xFFBAEAFF, 0xFF00677F, 0xFF004D61, 0xFFBAEAFF),
    Lime(0xFFCDEDA3, 0xFF4C6700, 0xFF384E00, 0xFFCDEDA3),
    Gray(0xFFDEE3EB, 0xFF56606B, 0xFF3F4851, 0xFFDEE3EB),
    ;

    /** Container and icon colour, pulled a little towards the theme so they sit with Material You. */
    @Composable
    fun colors(): Pair<Color, Color> {
        val scheme = MaterialTheme.colorScheme
        val dark = scheme.surface.luminance() < 0.5f
        val icon = lerp(Color(if (dark) darkIcon else lightIcon), scheme.onPrimaryContainer, THEME_PULL)
        if (!dark) return lerp(Color(lightContainer), scheme.primaryContainer, THEME_PULL) to icon
        // Tone 30 alone reads muddy for orange and yellow on black; some grey calms all of them.
        val container = lerp(lerp(Color(darkContainer), scheme.primaryContainer, THEME_PULL), scheme.surfaceContainerHighest, DARK_CALM)
        return container to icon
    }
}

private const val THEME_PULL = 0.15f
private const val DARK_CALM = 0.3f

@Composable
internal fun CategoryIcon(icon: ImageVector, hue: CategoryHue? = null) {
    val (container, tint) = hue?.colors() ?: (MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(container),
    ) {
        Icon(icon, contentDescription = null, tint = tint)
    }
}

@Composable
internal fun transparentItem() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
internal fun LinkItem(title: Int, summary: Int, url: String) =
    LinkItem(stringResource(title), stringResource(summary), url)

/** A settings row that opens a link in the browser. */
@Composable
internal fun LinkItem(title: String, summary: String, url: String) {
    val context = LocalContext.current
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { LeavesAppMark() },
        colors = transparentItem(),
        modifier = Modifier.tileClickable { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) },
    )
}

/** Marks a row that opens another app, such as the browser or the Android settings. */
@Composable
internal fun LeavesAppMark() {
    Icon(
        painterResource(R.drawable.ic_open_in_new),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Marks a row that opens a page or a sheet. */
@Composable
internal fun NavChevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
