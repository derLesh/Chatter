package dev.chatter.app.ui.settings

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.delay

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

/** Long enough for the page to finish sliding in; scrolling during the slide would miss. */
private const val REVEAL_DELAY_MS = 350L

/**
 * Related settings as separate tiles with small gaps (Android 16 style): strong outer corners,
 * slight ones between tiles.
 */
@Composable
internal fun SettingsGroup(title: Int? = null, build: GroupScope.() -> Unit) {
    val items = GroupScope().apply(build).items
    val target = LocalSettingsTarget.current
    val (groupReveal, groupGlow) = reveal(title != null && title == target)
    Column(groupReveal) {
        if (title != null) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items.forEachIndexed { i, (key, content) ->
                val (tileReveal, tileGlow) = reveal(key != null && key == target)
                Surface(
                    color = lerp(
                        MaterialTheme.colorScheme.surfaceContainer,
                        MaterialTheme.colorScheme.primaryContainer,
                        maxOf(groupGlow, tileGlow),
                    ),
                    shape = tileShape(i, items.size),
                    modifier = Modifier.fillMaxWidth().then(tileReveal),
                ) {
                    Box(Modifier.padding(vertical = 2.dp)) { content() }
                }
            }
        }
    }
}

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

private fun tileShape(index: Int, count: Int): RoundedCornerShape {
    val outer = 24.dp
    val inner = 4.dp
    return RoundedCornerShape(
        topStart = if (index == 0) outer else inner,
        topEnd = if (index == 0) outer else inner,
        bottomStart = if (index == count - 1) outer else inner,
        bottomEnd = if (index == count - 1) outer else inner,
    )
}

@Composable
internal fun CategoryIcon(icon: ImageVector) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
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
        trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
        colors = transparentItem(),
        modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) },
    )
}
