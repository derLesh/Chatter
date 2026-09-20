package dev.chatter.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import dev.chatter.app.R
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.net.HelixUser
import dev.chatter.app.ui.UserCardData
import dev.chatter.app.ui.theme.readableNameColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Opens on tap / long press of a message: who wrote it (avatar, badges, account age, bio),
 * what you can do with it, and the user's recent messages in this channel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserCardSheet(
    item: ChatItem,
    canModerate: Boolean,
    style: ChatStyle,
    imageLoader: ImageLoader,
    load: suspend () -> UserCardData,
    blocked: Boolean,
    onBlock: (HelixUser, Boolean) -> Unit,
    onReply: () -> Unit,
    onMention: () -> Unit,
    onDelete: () -> Unit,
    onTimeout: () -> Unit,
    onBan: () -> Unit,
    onDismiss: () -> Unit,
) {
    var data by remember(item.id) { mutableStateOf<UserCardData?>(null) }
    LaunchedEffect(item.id) { data = load() }

    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val isUserMessage = item.login != null
    val canReply = item.canReply && !item.id.startsWith("local-")

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        LazyColumn(Modifier.fillMaxWidth()) {
            if (isUserMessage) {
                item { Header(item, data, style, imageLoader) }
                data?.user?.description?.takeIf { it.isNotBlank() }?.let { bio ->
                    item {
                        Text(
                            text = bio,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            // The message that was tapped.
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        MessageRow(item.copy(historical = false), style, imageLoader, onAction = {})
                    }
                }
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    if (canReply) ActionButton(Icons.AutoMirrored.Filled.Send, R.string.action_reply) { onReply(); onDismiss() }
                    if (isUserMessage) ActionButton(Icons.Default.Person, R.string.action_mention) { onMention(); onDismiss() }
                    ActionButton(Icons.Default.Share, R.string.action_copy) {
                        clipboard.setText(AnnotatedString(item.text.ifEmpty { item.systemText.orEmpty() }))
                        onDismiss()
                    }
                }
            }

            // Blocking needs the profile (for the Twitch id), so it waits for the card to load.
            data?.user?.takeIf { isUserMessage && !item.isOwn }?.let { user ->
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        ModButton(
                            icon = if (blocked) Icons.Default.Check else Icons.Default.Clear,
                            label = if (blocked) R.string.action_unblock else R.string.action_block,
                            danger = !blocked,
                        ) { onBlock(user, !blocked); onDismiss() }
                    }
                }
            }

            if (canModerate && isUserMessage && !item.isOwn) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        if (!item.id.startsWith("local-") && !item.deleted) {
                            ModButton(Icons.Default.Delete, R.string.action_delete, danger = false) { onDelete(); onDismiss() }
                        }
                        ModButton(Icons.Default.Warning, R.string.action_timeout, danger = false) { onTimeout(); onDismiss() }
                        ModButton(Icons.Default.Lock, R.string.action_ban, danger = true) { onBan(); onDismiss() }
                    }
                }
            }

            if (isUserMessage) {
                item {
                    HorizontalDivider(Modifier.padding(top = 12.dp))
                    Text(
                        text = stringResource(R.string.user_recent_messages),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                val recent = data?.recentMessages
                when {
                    recent == null -> item {
                        CircularProgressIndicator(Modifier.padding(20.dp).size(24.dp), strokeWidth = 2.dp)
                    }
                    recent.isEmpty() -> item {
                        Text(
                            stringResource(R.string.user_no_messages),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    else -> items(recent.asReversed(), key = { "r" + it.id }) { m ->
                        MessageRow(m, style, imageLoader, onAction = {})
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Header(item: ChatItem, data: UserCardData?, style: ChatStyle, imageLoader: ImageLoader) {
    val user = data?.user
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        AsyncImage(
            model = user?.profileImageUrl,
            contentDescription = null,
            imageLoader = imageLoader,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.displayName ?: item.login.orEmpty(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = readableNameColor(item.color, item.login, style.dark, style.nameColors),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOfNotNull(
                item.login?.takeIf { !it.equals(item.displayName, ignoreCase = true) }?.let { "@$it" },
                when (user?.broadcasterType) {
                    "partner" -> stringResource(R.string.user_partner)
                    "affiliate" -> stringResource(R.string.user_affiliate)
                    else -> null
                },
            ).joinToString(" \u00B7 ")
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            formatDate(user?.createdAt)?.let {
                Text(
                    stringResource(R.string.user_created, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.badges.isNotEmpty()) BadgeRow(item, imageLoader)
        }
    }
}

/** Badges in a single line below the name; scrolls sideways if there are many. */
@Composable
private fun BadgeRow(item: ChatItem, imageLoader: ImageLoader) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
    ) {
        item.badges.forEach { badge ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                AsyncImage(model = badge.url, contentDescription = null, imageLoader = imageLoader, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    badge.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Buttons share one row equally; labels are cut off rather than wrapping to a second line. */
private val CompactPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)

@Composable
private fun RowScope.ActionButton(icon: ImageVector, label: Int, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, contentPadding = CompactPadding, modifier = Modifier.weight(1f)) {
        ButtonContent(icon, label)
    }
}

@Composable
private fun RowScope.ModButton(icon: ImageVector, label: Int, danger: Boolean, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    OutlinedButton(
        onClick = onClick,
        contentPadding = CompactPadding,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        modifier = Modifier.weight(1f),
    ) {
        ButtonContent(icon, label)
    }
}

@Composable
private fun ButtonContent(icon: ImageVector, label: Int) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(6.dp))
    Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private fun formatDate(iso: String?): String? = iso?.takeIf { it.isNotEmpty() }?.let {
    runCatching {
        Instant.parse(it).atZone(ZoneId.systemDefault()).toLocalDate()
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }.getOrNull()
}
