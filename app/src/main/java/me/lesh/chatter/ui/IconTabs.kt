package me.lesh.chatter.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** One of [IconTabs]. [count] is a number beside the chosen tab's name, such as recent messages. */
data class IconTab(val label: String, val icon: Int, val count: Int? = null)

/**
 * Tabs the way X draws a profile's: the chosen one grows and shows its name, the others keep only
 * their icon, and the line below slides along.
 */
@Composable
fun IconTabs(tabs: List<IconTab>, selected: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    // Left edge and width of each tab in px, as last laid out; the line follows the chosen one.
    val bounds = remember(tabs.size) { mutableStateMapOf<Int, Pair<Float, Float>>() }
    Box(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(48.dp)) {
            tabs.forEachIndexed { i, tab ->
                val chosen = i == selected
                val weight by animateFloatAsState(if (chosen) 2.2f else 1f, label = "tab weight")
                val color = if (chosen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(weight)
                        .fillMaxHeight()
                        .onPlaced { bounds[i] = it.positionInParent().x to it.size.width.toFloat() }
                        .selectable(selected = chosen, role = Role.Tab, onClick = { onPick(i) }),
                ) {
                    Icon(
                        ImageVector.vectorResource(tab.icon),
                        contentDescription = if (chosen) null else tab.label,
                        tint = color,
                        modifier = Modifier.size(20.dp),
                    )
                    AnimatedVisibility(
                        visible = chosen,
                        enter = fadeIn() + expandHorizontally(),
                        exit = fadeOut() + shrinkHorizontally(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(8.dp))
                            Text(tab.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
                            if (tab.count != null) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    tab.count.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(Modifier.align(Alignment.BottomStart), color = MaterialTheme.colorScheme.surfaceContainerHighest)
        bounds[selected]?.let { (left, width) ->
            val x by animateFloatAsState(left, label = "tab line x")
            val w by animateFloatAsState(width, label = "tab line width")
            val density = LocalDensity.current
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset { IntOffset(x.roundToInt(), 0) }
                    .width(with(density) { w.toDp() })
                    .height(3.dp)
                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
