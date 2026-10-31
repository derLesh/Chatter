package dev.chatter.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import dev.chatter.app.R
import dev.chatter.app.badges.Badge
import dev.chatter.app.badges.BadgeProvider

/**
 * The name of a kind of badge. A Twitch badge's title names one version ("1-Year Subscriber"), so
 * the common sets get a name of their own; the rest keep the [title] of the badge seen.
 */
@Composable
fun badgeKindName(provider: BadgeProvider, set: String, title: String): String {
    if (provider != BadgeProvider.Twitch) return title
    val name = TWITCH_KIND_NAMES[set] ?: return title
    return stringResource(name)
}

/** Whether a badge is one people know by name, which the settings list shows without a search. */
fun isWellKnownBadge(provider: BadgeProvider, set: String) = provider == BadgeProvider.Twitch && set in TWITCH_KIND_NAMES

/** Asks before leaving every badge of [badge]'s kind out of the chat. */
@Composable
fun HideBadgeDialog(badge: Badge, imageLoader: ImageLoader, onHide: () -> Unit, onDismiss: () -> Unit) {
    val name = badgeKindName(badge.provider, badge.set, badge.title)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.badge_hide_title)) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(model = badge.url, contentDescription = null, imageLoader = imageLoader, modifier = Modifier.size(32.dp))
                Text(stringResource(R.string.badge_hide_text, name))
            }
        },
        confirmButton = { TextButton(onClick = { onHide(); onDismiss() }) { Text(stringResource(R.string.badge_hide_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Twitch's set ids for the badges people know by name. */
private val TWITCH_KIND_NAMES = mapOf(
    "subscriber" to R.string.badge_kind_subscriber,
    "founder" to R.string.badge_kind_founder,
    "bits" to R.string.badge_kind_bits,
    "bits-leader" to R.string.badge_kind_bits_leader,
    "sub-gifter" to R.string.badge_kind_sub_gifter,
    "sub-gift-leader" to R.string.badge_kind_gift_leader,
    "predictions" to R.string.badge_kind_prediction,
    "hype-train" to R.string.badge_kind_hype_train,
    "premium" to R.string.badge_kind_prime,
    "turbo" to R.string.badge_kind_turbo,
    "partner" to R.string.badge_kind_verified,
    "broadcaster" to R.string.role_broadcaster,
    "moderator" to R.string.role_moderator,
    "vip" to R.string.role_vip,
)
