package me.lesh.chatter.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import me.lesh.chatter.R
import me.lesh.chatter.chat.HeldMessage
import me.lesh.chatter.eventsub.HoldReason

/**
 * The oldest message AutoMod holds in a channel the user moderates, floating over the chat with
 * buttons to let it in or deny it. The next one takes its place; an empty [held] lets the bar leave.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AutoModBar(
    held: List<HeldMessage>,
    style: ChatStyle,
    imageLoader: ImageLoader,
    onResolve: (HeldMessage, allow: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    // The bar leaves with the last message it showed, not empty.
    var shown by remember { mutableStateOf(held) }
    if (held.isNotEmpty()) shown = held
    AnimatedVisibility(
        visible = held.isNotEmpty(),
        enter = scaleIn(motion.defaultSpatialSpec(), initialScale = 0.8f, transformOrigin = TransformOrigin(0.5f, 0f)) +
            fadeIn(motion.defaultEffectsSpec()),
        exit = scaleOut(motion.fastSpatialSpec(), targetScale = 0.8f, transformOrigin = TransformOrigin(0.5f, 0f)) +
            fadeOut(motion.fastEffectsSpec()),
        modifier = modifier,
    ) {
        shown.firstOrNull()?.let { Bar(it, shown.size, style, imageLoader, onResolve) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Bar(
    first: HeldMessage,
    count: Int,
    style: ChatStyle,
    imageLoader: ImageLoader,
    onResolve: (HeldMessage, allow: Boolean) -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 6.dp,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
                Icon(
                    ImageVector.vectorResource(R.drawable.ic_shield),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    pluralStringResource(R.plurals.automod_held, count, count),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            // Slides the next message in, so a mod working through the queue sees it change.
            AnimatedContent(
                targetState = first,
                contentKey = { it.item.id },
                transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
                label = "held",
            ) { held ->
                Column {
                    val item = remember(held.item) { held.item.copy(historical = false) }
                    MessageRow(item, style, imageLoader, onGesture = null)
                    Text(
                        reasonText(held.reason),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    ConnectedButtons(
                        listOf(
                            ButtonAction(R.string.automod_deny, danger = true) { onResolve(held, false) },
                            ButtonAction(R.string.automod_allow) { onResolve(held, true) },
                        ),
                        tonal = true,
                        compact = true,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun reasonText(reason: HoldReason): String = when (reason) {
    HoldReason.BlockedTerm -> stringResource(R.string.automod_blocked_term)
    is HoldReason.AutoMod -> {
        val category = AUTOMOD_CATEGORIES[reason.category]?.let { stringResource(it) }
            ?: reason.category.replace('_', ' ')
        if (reason.level > 0) stringResource(R.string.automod_reason_level, category, reason.level) else category
    }
}

/** Twitch's AutoMod categories; it has named some of them in two ways. */
private val AUTOMOD_CATEGORIES = mapOf(
    "aggressive" to R.string.automod_aggression,
    "aggression" to R.string.automod_aggression,
    "bullying" to R.string.automod_bullying,
    "disability" to R.string.automod_disability,
    "sexuality" to R.string.automod_sexuality,
    "sexuality_sex_or_gender" to R.string.automod_sexuality,
    "misogyny" to R.string.automod_misogyny,
    "race_ethnicity_or_religion" to R.string.automod_race,
    "racism" to R.string.automod_race,
    "sex_based_terms" to R.string.automod_sex_terms,
    "swearing" to R.string.automod_swearing,
    "profanity" to R.string.automod_swearing,
)
