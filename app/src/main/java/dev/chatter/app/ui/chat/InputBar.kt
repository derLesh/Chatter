package dev.chatter.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import dev.chatter.app.R
import dev.chatter.app.channels.ChannelInfo
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.ui.Suggestion
import dev.chatter.app.ui.channels.ChannelAvatar

/**
 * The field a message is written in, with the emote picker, the suggestions and the reply strip.
 *
 * On a combined chat [sendChannels] are its channels, and a picture in front of the field says
 * which of them [sendChannel] the message goes to; tapping it picks another. With a single channel
 * there is nothing to pick, and the field looks as it always did.
 */
@Composable
fun InputBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    enabled: Boolean,
    replyTo: ChatItem?,
    suggestions: List<Suggestion>,
    imageLoader: ImageLoader,
    onSuggestion: (Suggestion) -> Unit,
    onCancelReply: () -> Unit,
    onEmotePicker: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    sendChannels: List<String> = emptyList(),
    sendChannel: String? = null,
    channelInfo: Map<String, ChannelInfo> = emptyMap(),
    onSendChannel: (String) -> Unit = {},
) {
    val choosing = sendChannels.size > 1 && sendChannel != null
    val focus = remember { FocusRequester() }
    // Picking a message to answer is only half of answering it: the keyboard comes up with it,
    // so that one tap on a message is all it takes to start typing.
    LaunchedEffect(replyTo?.id) { if (replyTo != null && enabled) focus.requestFocus() }
    // No bar of its own: the input sits straight on the chat background, so only the rounded
    // field, the chips and the reply strip stand out.
    Column(modifier) {
        if (suggestions.isNotEmpty()) {
            SuggestionRow(suggestions, imageLoader, onSuggestion)
        }
        if (replyTo != null) {
            ReplyBar(replyTo, onCancelReply)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            if (choosing) {
                SendChannelPicker(sendChannels, sendChannel!!, channelInfo, imageLoader, enabled, onSendChannel)
            }
            IconButton(onClick = onEmotePicker, enabled = enabled) {
                Icon(Icons.Default.Face, contentDescription = stringResource(R.string.emotes))
            }
            TextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                placeholder = {
                    Text(
                        when {
                            !enabled -> stringResource(R.string.input_hint_disabled)
                            choosing -> stringResource(R.string.input_hint_in, channelInfo[sendChannel]?.displayName ?: sendChannel!!)
                            else -> stringResource(R.string.input_hint)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                shape = RoundedCornerShape(20.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            IconButton(onClick = onSend, enabled = enabled && value.text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
            }
        }
    }
}

/** The channel a combined chat's message goes to, as its picture, with the others a tap away. */
@Composable
private fun SendChannelPicker(
    channels: List<String>,
    selected: String,
    info: Map<String, ChannelInfo>,
    imageLoader: ImageLoader,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Box(Modifier.semantics { contentDescription = info[selected]?.displayName ?: selected }) {
                ChannelAvatar(info[selected], imageLoader, 28.dp)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                stringResource(R.string.send_in_channel),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            channels.forEach { login ->
                DropdownMenuItem(
                    leadingIcon = { ChannelAvatar(info[login], imageLoader, 28.dp) },
                    text = {
                        Text(
                            info[login]?.displayName ?: login,
                            color = if (login == selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            maxLines = 1,
                        )
                    },
                    trailingIcon = { if (login == selected) Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = { open = false; onSelect(login) },
                )
            }
        }
    }
}

@Composable
private fun SuggestionRow(suggestions: List<Suggestion>, imageLoader: ImageLoader, onClick: (Suggestion) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(suggestions, key = {
            when (it) {
                is Suggestion.EmoteSuggestion -> "e:" + it.emote.name
                is Suggestion.UserSuggestion -> "u:" + it.name
                is Suggestion.CommandSuggestion -> "c:" + it.name
            }
        }) { s ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable { onClick(s) }
                    .padding(horizontal = 10.dp),
            ) {
                when (s) {
                    is Suggestion.EmoteSuggestion -> {
                        AsyncImage(
                            model = s.emote.url,
                            contentDescription = null,
                            imageLoader = imageLoader,
                            onSuccess = EmoteSizes.onLoaded(s.emote),
                            modifier = Modifier.size(width = (26 * EmoteSizes.aspectRatio(s.emote)).coerceAtMost(60f).dp, height = 26.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(s.emote.name, style = MaterialTheme.typography.bodyMedium)
                    }
                    is Suggestion.UserSuggestion -> Text("@" + s.name, style = MaterialTheme.typography.bodyMedium)
                    is Suggestion.CommandSuggestion -> Text(s.usage, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ReplyBar(item: ChatItem, onCancel: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.replying_to, item.displayName ?: item.login.orEmpty(), item.text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onCancel) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
        }
    }
}
