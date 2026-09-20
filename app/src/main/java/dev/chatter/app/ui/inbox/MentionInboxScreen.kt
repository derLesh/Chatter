package dev.chatter.app.ui.inbox

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import dev.chatter.app.R
import dev.chatter.app.channels.ChannelInfo
import dev.chatter.app.chat.InboxMention
import dev.chatter.app.ui.MainViewModel
import dev.chatter.app.ui.channels.ChannelAvatar

/**
 * Every mention across all channels, newest first. The chat buffers forget, this does not: it is
 * where the user finds out who wanted something from them while the app was closed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MentionInboxScreen(vm: MainViewModel, onOpenChannel: (String) -> Unit, onBack: () -> Unit) {
    // Without this, back would leave the app instead of going back to the chat behind it.
    BackHandler(onBack = onBack)

    val mentions by vm.inboxMentions.collectAsStateWithLifecycle()
    val info by vm.channelInfo.collectAsStateWithLifecycle()
    val nicknames by vm.nicknames.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inbox_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (mentions.isNotEmpty()) {
                        IconButton(onClick = vm::markInboxRead) {
                            Icon(Icons.Default.Check, stringResource(R.string.inbox_mark_all_read))
                        }
                        IconButton(onClick = vm::clearInbox) {
                            Icon(Icons.Default.Delete, stringResource(R.string.inbox_clear))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (mentions.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
            ) {
                Text(stringResource(R.string.inbox_empty), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.inbox_empty_hint),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(mentions, key = { it.id }) { mention ->
                MentionRow(
                    mention = mention,
                    channelName = info[mention.channel]?.displayName ?: mention.channel,
                    name = nicknames[mention.login.lowercase()] ?: mention.displayName,
                    channel = info[mention.channel],
                    imageLoader = vm.imageLoader,
                    onClick = {
                        vm.markInboxRead(mention)
                        onOpenChannel(mention.channel)
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
            }
        }
    }
}

@Composable
private fun MentionRow(
    mention: InboxMention,
    channelName: String,
    name: String,
    channel: ChannelInfo?,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
) {
    val time = DateUtils.getRelativeTimeSpanString(
        mention.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
    ).toString()
    ListItem(
        headlineContent = {
            Text(
                stringResource(R.string.inbox_from, name, channelName),
                fontWeight = if (mention.read) FontWeight.Normal else FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = { Text(mention.text, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Box(contentAlignment = Alignment.TopEnd) {
                ChannelAvatar(channel, imageLoader, 40.dp)
                if (!mention.read) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        },
        trailingContent = { Text(time, style = MaterialTheme.typography.labelSmall) },
        colors = ListItemDefaults.colors(
            containerColor = if (mention.read) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}
