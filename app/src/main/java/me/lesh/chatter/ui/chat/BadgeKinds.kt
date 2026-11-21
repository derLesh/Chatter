package me.lesh.chatter.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import me.lesh.chatter.R
import me.lesh.chatter.badges.Badge
import me.lesh.chatter.badges.BadgeProvider
import me.lesh.chatter.ui.FormSheet
import me.lesh.chatter.ui.theme.isAppInDarkTheme

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

/** The badge picture that suits the app theme; see [Badge.urlOnLight]. */
@Composable
@ReadOnlyComposable
fun badgePicture(url: String?, urlOnLight: String?): String? =
    if (urlOnLight != null && !isAppInDarkTheme()) urlOnLight else url

/** The colour behind a badge's picture, where its provider has one; see [Badge.background]. */
fun Modifier.badgeBackground(color: Int?): Modifier =
    if (color == null) this else background(Color(color), RoundedCornerShape(percent = 15))

/** Whether a badge is one people know by name, which the settings list shows without a search. */
fun isWellKnownBadge(provider: BadgeProvider, set: String) = provider == BadgeProvider.Twitch && set in TWITCH_KIND_NAMES

/** Asks before leaving every badge of [badge]'s kind out of the chat. */
@Composable
fun HideBadgeSheet(badge: Badge, imageLoader: ImageLoader, onHide: () -> Unit, onDismiss: () -> Unit) {
    val name = badgeKindName(badge.provider, badge.set, badge.title)
    FormSheet(
        title = stringResource(R.string.badge_hide_title),
        confirmLabel = stringResource(R.string.badge_hide_confirm),
        onConfirm = onHide,
        onDismiss = onDismiss,
    ) {
        item(key = "badge") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                AsyncImage(
                    model = badgePicture(badge.url, badge.urlOnLight),
                    contentDescription = null,
                    imageLoader = imageLoader,
                    modifier = Modifier.size(32.dp).badgeBackground(badge.background),
                )
                Text(
                    stringResource(R.string.badge_hide_text, name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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
