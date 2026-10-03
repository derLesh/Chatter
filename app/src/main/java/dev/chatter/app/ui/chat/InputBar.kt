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
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.DropdownMenuPopupPositionProvider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import dev.chatter.app.R
import dev.chatter.app.channels.ChannelInfo
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.ChatRestriction
import dev.chatter.app.chat.SendLimits
import dev.chatter.app.ui.Suggestion
import dev.chatter.app.ui.channels.ChannelAvatar
import kotlinx.coroutines.delay

/**
 * The field a message is written in, with the emote picker, the suggestions and the reply strip.
 *
 * On a combined chat [sendChannels] are its channels, and a picture in front of the field says
 * which of them [sendChannel] the message goes to; tapping it picks another. With a single channel
 * there is nothing to pick, and the field looks as it always did.
 *
 * Without [onCancelReply] the reply strip has no way to be closed: in a conversation, answering
 * into it is the point.
 *
 * [replyStarts] counts the answers the user began, and each one brings the keyboard up. The
 * answer itself cannot say so: it also comes back with the draft of a page swiped back to.
 *
 * What Twitch would refuse is said here first: the characters left once a message gets long,
 * the seconds of slow mode on the send button, and a [restriction] in place of the placeholder.
 * [waitUntil] is when slow mode lets the user write again.
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
    onCancelReply: (() -> Unit)?,
    onEmotePicker: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    replyStarts: Int = 0,
    waitUntil: Long = 0L,
    restriction: ChatRestriction? = null,
    sendChannels: List<String> = emptyList(),
    sendChannel: String? = null,
    channelInfo: Map<String, ChannelInfo> = emptyMap(),
    onSendChannel: (String) -> Unit = {},
) {
    val choosing = sendChannels.size > 1 && sendChannel != null
    val focus = remember { FocusRequester() }
    // Picking a message to answer is only half of answering it: the keyboard comes up with it,
    // so that one tap on a message is all it takes to start typing.
    LaunchedEffect(replyStarts) { if (replyTo != null && enabled) focus.requestFocus() }
    var waitLeft by remember { mutableIntStateOf(0) }
    LaunchedEffect(waitUntil) {
        while (true) {
            val left = ((waitUntil - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0)
            waitLeft = left
            if (left == 0) break
            delay(250)
        }
    }
    val length = remember(value.text) { SendLimits.length(value.text) }
    val tooLong = length > SendLimits.MAX_LENGTH
    val canSend = enabled && value.text.isNotBlank() && !tooLong && waitLeft == 0
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
                SendChannelPicker(sendChannels, sendChannel, channelInfo, imageLoader, enabled, onSendChannel)
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
                            restriction != null -> stringResource(restriction.hint)
                            choosing -> stringResource(R.string.input_hint_in, channelInfo[sendChannel]?.displayName ?: sendChannel)
                            else -> stringResource(R.string.input_hint)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingIcon = if (length < SendLimits.COUNTER_FROM) null else {
                    {
                        val left = SendLimits.MAX_LENGTH - length
                        val said = if (tooLong) pluralStringResource(R.plurals.characters_too_many, -left, -left)
                        else pluralStringResource(R.plurals.characters_left, left, left)
                        Text(
                            "$left",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (tooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { contentDescription = said },
                        )
                    }
                },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
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
            IconButton(onClick = onSend, enabled = canSend) {
                if (waitLeft > 0) {
                    val wait = pluralStringResource(R.plurals.send_wait, waitLeft, waitLeft)
                    Text(
                        "$waitLeft",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.semantics { contentDescription = wait },
                    )
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
                }
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
        DropdownMenuPopup(
            expanded = open,
            onDismissRequest = { open = false },
            popupPositionProvider = remember { OnTopOfAnchor() },
        ) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                MenuDefaults.DropdownMenuGroupLabel {
                    Text(stringResource(R.string.send_in_channel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                channels.forEachIndexed { index, login ->
                    SelectableDropdownMenuItem(
                        selected = login == selected,
                        onClick = { open = false; onSelect(login) },
                        text = { Text(info[login]?.displayName ?: login, maxLines = 1) },
                        shapes = MenuDefaults.itemShape(index, channels.size),
                        leadingIcon = { ChannelAvatar(info[login], imageLoader, 28.dp) },
                        trailingContent = { if (login == selected) Icon(Icons.Default.Check, contentDescription = null) },
                    )
                }
            }
        }
    }
}

/**
 * Sets a menu straight on top of what opened it. Material's own positioning keeps every menu 48dp
 * clear of the window's edges, and the send picker sits closer to the bottom than that, so its
 * menu would float a good way above the field it belongs to.
 */
private class OnTopOfAnchor : DropdownMenuPopupPositionProvider {
    override var transformOrigin by mutableStateOf(TransformOrigin(0f, 1f))
        private set

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val ltr = layoutDirection == LayoutDirection.Ltr
        // Opening from the anchor's corner, so it grows out of the picture that was tapped.
        transformOrigin = TransformOrigin(if (ltr) 0f else 1f, 1f)
        val x = if (ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            (anchorBounds.top - popupContentSize.height).coerceAtLeast(0),
        )
    }
}

/** What the field says in place of its placeholder while this is in the way. */
private val ChatRestriction.hint: Int
    get() = when (this) {
        ChatRestriction.SubsOnly -> R.string.input_hint_subs_only
        ChatRestriction.FollowersOnly -> R.string.input_hint_followers_only
        ChatRestriction.EmoteOnly -> R.string.input_hint_emote_only
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
private fun ReplyBar(item: ChatItem, onCancel: (() -> Unit)?) {
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
        if (onCancel != null) IconButton(onClick = onCancel) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
        } else Spacer(Modifier.height(48.dp))
    }
}

/**
 * Where the field sits for a guest. Reading needs no account and writing does, so instead of a
 * field that could never send, this says so and leads to the login.
 */
@Composable
fun GuestBar(onLogIn: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Text(
            stringResource(R.string.guest_bar),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(onClick = onLogIn) { Text(stringResource(R.string.guest_log_in)) }
    }
}
