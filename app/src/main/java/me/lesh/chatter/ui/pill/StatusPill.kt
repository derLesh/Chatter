package me.lesh.chatter.ui.pill

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.delay

/**
 * A pill that stays up while there is [content] and changes in place: the icon crossfades, and the
 * new text rises in as the old one leaves upwards while the pill takes its width.
 *
 * [settleDelayMs]: how long a busy or offline state waits before it shows, since both often end
 * within a second (a quick load, a network handover) and would only flicker. Anything else shows at once.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatusPill(content: PillContent?, modifier: Modifier = Modifier, settleDelayMs: Long = 0) {
    var shown by remember { mutableStateOf<PillContent?>(null) }
    // Kept while the pill slides away, so it leaves with the text it had.
    var lastShown by remember { mutableStateOf<PillContent?>(null) }
    LaunchedEffect(content) {
        val settles = content?.tone == PillTone.Busy || content?.tone == PillTone.Offline
        if (settles && shown == null) delay(settleDelayMs)
        if (content != null) lastShown = content
        shown = content
    }
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = shown != null,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = modifier,
    ) {
        val current = lastShown ?: return@AnimatedVisibility
        // Read out as one, and only when what it says changes, not with every percent of an upload.
        val announced = current.spoken ?: current.text
        PillSurface(
            Modifier.widthIn(max = PillMaxWidth).clearAndSetSemantics {
                contentDescription = announced
                liveRegion = LiveRegionMode.Polite
            },
        ) {
            PillRow(
                icon = { Crossfade(current.tone, animationSpec = motion.defaultEffectsSpec(), label = "pill icon") { PillIcon(it) } },
                action = null,
            ) { textModifier ->
                AnimatedContent(
                    current,
                    transitionSpec = {
                        (slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(motion.defaultEffectsSpec()))
                            .togetherWith(slideOutVertically(motion.defaultSpatialSpec()) { -it } + fadeOut(motion.fastEffectsSpec()))
                            .using(SizeTransform { _, _ -> motion.defaultSpatialSpec() })
                    },
                    contentAlignment = Alignment.CenterStart,
                    label = "pill text",
                    modifier = textModifier,
                ) { PillText(it) }
            }
        }
    }
}
