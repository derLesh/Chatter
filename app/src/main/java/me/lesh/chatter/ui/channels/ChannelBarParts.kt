package me.lesh.chatter.ui.channels

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.lesh.chatter.R
import me.lesh.chatter.chat.ChatRole

/** Twitch's colors for the roles it gives a badge; null for a plain viewer. */
internal val ChatRole.color: Color?
    get() = when (this) {
        ChatRole.Broadcaster -> Color(0xFFE91916)
        ChatRole.Moderator -> Color(0xFF00AD03)
        ChatRole.Vip -> Color(0xFFE005B9)
        ChatRole.Viewer -> null
    }

/** The role's name; null for a plain viewer. */
internal val ChatRole.label: Int?
    get() = when (this) {
        ChatRole.Broadcaster -> R.string.role_broadcaster
        ChatRole.Moderator -> R.string.role_moderator
        ChatRole.Vip -> R.string.role_vip
        ChatRole.Viewer -> null
    }

/** Next to a page's name: it has an unsent draft. */
@Composable
internal fun DraftMark() {
    Icon(
        Icons.Default.Edit,
        contentDescription = stringResource(R.string.draft_waiting),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(14.dp),
    )
}

internal fun formatCount(n: Int): String = if (n > 999) "999+" else n.toString()

fun formatViewers(n: Int): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.getDefault(), "%.1fM", n / 1_000_000f)
    n >= 1_000 -> String.format(java.util.Locale.getDefault(), "%.1fK", n / 1_000f)
    else -> n.toString()
}
