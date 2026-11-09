package me.lesh.chatter.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import coil3.compose.AsyncImage
import me.lesh.chatter.R
import me.lesh.chatter.channels.ChannelInfo
import me.lesh.chatter.chat.ChatItem
import me.lesh.chatter.chat.ChatModes
import me.lesh.chatter.chat.ChatRestriction
import me.lesh.chatter.chat.Penalty
import me.lesh.chatter.chat.RoomState
import me.lesh.chatter.chat.SendLimits
import me.lesh.chatter.settings.RecentlySentButton
import me.lesh.chatter.settings.SendLaterButton
import me.lesh.chatter.ui.Suggestion
import me.lesh.chatter.ui.channels.ChannelAvatar
import me.lesh.chatter.ui.channels.formatMinutes
import me.lesh.chatter.ui.theme.readableNameColor
import me.lesh.chatter.ui.theme.isAppInDarkTheme
import me.lesh.chatter.ui.inbox.PersonAvatar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The message input with emote picker, suggestions and reply strip.
 *
 * On a combined chat [sendChannels] are its channels, and a picture in front of the input shows
 * [sendChannel]; tapping it picks another. Without [onCancelReply] the reply strip cannot be
 * closed, as in a conversation. Each increment of [replyStarts] opens the keyboard; the reply
 * itself cannot signal that, since it also returns with a page's draft. With [replyCard] the
 * strip is a card with [replyPictureUrl], the picture of the person answered, if known.
 * [onCoveredHeight] gets the input's height without the reply, which floats over the chat instead
 * of pushing it up.
 *
 * Twitch's limits are shown before sending: remaining characters for long messages, slow mode
 * seconds on the send button ([waitUntil]), and a [restriction] instead of the placeholder. A
 * timeout ([penalty]) counts down there too and turns sending off until it ends; a ban only says
 * so, since Twitch does not announce when it is lifted and a message is the only way to find out.
 *
 * While the input is empty a clock opens the user's last messages ([recentlySent]) above it, on
 * the send button or in the input as [recentlySentButton] says; a tap on one puts it back into
 * the input ([onReuseSent]).
 *
 * Given [chatModes], those of the channel the input sends to when the user moderates it, a shield
 * in front of the emotes opens them above the input; a change goes to [onChatModes] as the wished
 * state.
 *
 * With [sendLater] a message can be planned to go out later, and the planned ones show above the
 * input.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    recentlySent: suspend () -> List<String>,
    onReuseSent: (String) -> Unit,
    modifier: Modifier = Modifier,
    replyStarts: Int = 0,
    waitUntil: Long = 0L,
    restriction: ChatRestriction? = null,
    penalty: Penalty? = null,
    sendChannels: List<String> = emptyList(),
    sendChannel: String? = null,
    channelInfo: Map<String, ChannelInfo> = emptyMap(),
    onSendChannel: (String) -> Unit = {},
    recentlySentButton: RecentlySentButton = RecentlySentButton.ReplacesSend,
    chatModes: RoomState? = null,
    onChatModes: (RoomState) -> Unit = {},
    sendLater: SendLater? = null,
    buildStripe: Boolean = false,
    replyCard: Boolean = false,
    replyPictureUrl: String? = null,
    onCoveredHeight: ((Int) -> Unit)? = null,
) {
    val stripe = buildStripe && BuildStripe.offered
    val choosing = sendChannels.size > 1 && sendChannel != null
    val focus = remember { FocusRequester() }
    // Starting a reply opens the keyboard, so one tap on a message is enough to start typing.
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
    val timedOutUntil = (penalty as? Penalty.TimedOut)?.until ?: 0L
    var timeoutLeft by remember { mutableLongStateOf(0L) }
    LaunchedEffect(timedOutUntil) {
        while (true) {
            val left = ((timedOutUntil - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
            timeoutLeft = left
            if (left == 0L) break
            delay(250)
        }
    }
    val length = remember(value.text) { SendLimits.length(value.text) }
    val tooLong = length > SendLimits.MAX_LENGTH
    val canSend = enabled && value.text.isNotBlank() && !tooLong && waitLeft == 0 && timeoutLeft == 0L
    // Slow mode and a timeout end, so a message can be planned for after them.
    val canPlan = sendLater?.button != null && enabled && value.text.isNotBlank() && !tooLong
    var planning by rememberSaveable { mutableStateOf(false) }
    if (planning) {
        SendLaterSheet(value.text.trim(), onPlan = { sendLater?.onPlan(it) }, onDismiss = { planning = false })
    }
    val recentInInput = recentlySentButton == RecentlySentButton.InInput && value.text.isEmpty()
    // Slow mode's countdown keeps the button: it is what the user waits for right after sending.
    val recentOnSend = recentlySentButton == RecentlySentButton.ReplacesSend && value.text.isEmpty() && waitLeft == 0
    val offersRecent = recentInInput || recentOnSend
    val scope = rememberCoroutineScope()
    // One panel at a time above the input, so the chat keeps its room.
    var openPanel by remember { mutableStateOf<InputPanel?>(null) }
    val recentOpen = openPanel == InputPanel.RecentlySent
    val modesOpen = openPanel == InputPanel.ChatModes
    var recent by remember { mutableStateOf(emptyList<String>()) }
    val toggleRecent = {
        if (recentOpen) openPanel = null
        // Loaded before opening, so the panel unfolds at its final height.
        else scope.launch { recent = recentlySent(); openPanel = InputPanel.RecentlySent }
        Unit
    }
    // The list belongs to the channel the input sends to, and typing takes the button away.
    LaunchedEffect(offersRecent, enabled, sendChannel) {
        if (recentOpen && (!offersRecent || !enabled)) openPanel = null
    }
    val offersModes = chatModes != null
    LaunchedEffect(offersModes, enabled, sendChannel) {
        if (modesOpen && (!offersModes || !enabled)) openPanel = null
    }
    NavigationBackHandler(
        state = rememberNavigationEventState(currentInfo = NavigationEventInfo.None),
        isBackEnabled = openPanel != null,
        onBackCompleted = { openPanel = null },
    )
    // Not state: only read while measuring, where the reply is measured before the column around it.
    val replyHeight = remember { intArrayOf(0) }
    val covered = if (onCoveredHeight == null) Modifier else Modifier.onSizeChanged { onCoveredHeight(it.height - replyHeight[0]) }
    // No bar of its own: the input sits on the chat background.
    Column(modifier.then(covered)) {
        if (suggestions.isNotEmpty()) {
            // The field keeps its own gap above, as wide as the one below it.
            SuggestionRow(suggestions, imageLoader, onSuggestion, bottomGap = 0.dp)
        }
        AnimatedVisibility(recentOpen) {
            RecentlySentPanel(recent, onPick = { openPanel = null; onReuseSent(it) }, onClose = { openPanel = null })
        }
        AnimatedVisibility(modesOpen) {
            ChatModesPanel(chatModes ?: RoomState(), onChatModes, onClose = { openPanel = null })
        }
        if (sendLater != null && sendLater.waiting.isNotEmpty()) {
            // Two and a half strips at most: the cut one shows there are more to scroll to.
            Column(Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState())) {
                sendLater.waiting.forEach { message ->
                    ScheduledBar(
                        message,
                        canTakeBack = value.text.isBlank(),
                        onTakeBack = { sendLater.onTakeBack(message) },
                        onDrop = { sendLater.onDrop(message) },
                    )
                }
            }
        }
        // Always there, so the reply's height goes back to zero once it has slid away.
        Box(Modifier.onSizeChanged { replyHeight[0] = it.height }) {
            AnimatedReply(replyTo) { item ->
                if (replyCard) ReplyCard(item, replyPictureUrl, imageLoader, onCancelReply)
                else ReplyBar(item, onCancelReply)
            }
        }
        val leadingButtons = @Composable {
            if (choosing) {
                SendChannelPicker(sendChannels, sendChannel, channelInfo, imageLoader, enabled, onSendChannel)
            }
            if (offersModes) {
                PanelToggle(modesOpen, enabled, onClick = { openPanel = if (modesOpen) null else InputPanel.ChatModes }) {
                    Icon(painterResource(R.drawable.ic_shield), contentDescription = stringResource(R.string.chat_modes))
                }
            }
            IconButton(onClick = onEmotePicker, shapes = IconButtonDefaults.shapes(), enabled = enabled) {
                Icon(Icons.Default.Face, contentDescription = stringResource(R.string.emotes))
            }
        }
        val placeholder = @Composable {
            Text(
                when {
                    !enabled -> stringResource(R.string.input_hint_disabled)
                    timeoutLeft > 0 -> stringResource(R.string.input_hint_timed_out, timeLeft(timeoutLeft))
                    penalty == Penalty.Banned -> stringResource(R.string.input_hint_banned)
                    restriction != null -> stringResource(restriction.hint)
                    choosing -> stringResource(R.string.input_hint_in, channelInfo[sendChannel]?.displayName ?: sendChannel)
                    else -> stringResource(R.string.input_hint)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The placeholder is gone once something is typed; the timeout stays in sight here.
        val trailing: (@Composable () -> Unit)? = if (recentInInput) {
            { RecentlySentClock(recentOpen, enabled, toggleRecent) }
        } else if (timeoutLeft > 0 && value.text.isNotEmpty()) {
            {
                Text(
                    timeLeft(timeoutLeft),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        } else if (length < SendLimits.COUNTER_FROM) null else {
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
        }
        val sendButton = @Composable { modifier: Modifier ->
            if (recentOnSend) {
                RecentlySentClock(recentOpen, enabled, toggleRecent, modifier)
            } else {
                val hold = canPlan && sendLater?.button == SendLaterButton.HoldSend
                if (canPlan && sendLater?.button == SendLaterButton.OwnButton) {
                    // As far from Send as the clock in the input: the full field's clock sits at its
                    // edge, 4dp before Send; the slim field's sits right next to it.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = modifier,
                    ) {
                        SendLaterIconButton(onClick = { planning = true })
                        SendButton(canSend, waitLeft, onSend)
                    }
                } else {
                    SendButton(canSend, waitLeft, onSend, modifier.holdToSendLater(hold) { planning = true })
                }
            }
        }
        val keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send)
        val keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() })
        InputField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            placeholder = placeholder,
            leadingButtons = leadingButtons,
            trailing = trailing,
            sendButton = { sendButton(Modifier) },
            stripe = stripe,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).focusRequester(focus),
        )
    }
}

/**
 * The input as one rounded field with every button inside it, one touch target high. Built on
 * [BasicTextField], since Material's text field is never lower than 56dp.
 */
@Composable
private fun InputField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    enabled: Boolean,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    placeholder: @Composable () -> Unit,
    leadingButtons: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)?,
    sendButton: @Composable () -> Unit,
    stripe: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val fieldColor = if (enabled) colors.surfaceContainerHighest else colors.surfaceContainerHigh
    val textColor = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f)
    val placeholderColor = if (enabled) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.38f)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        maxLines = 4,
        modifier = modifier.fillMaxWidth(),
        decorationBox = { innerTextField ->
            // At the bottom, so the buttons stay by the thumb while a long message grows upwards.
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(fieldColor)
                    .buildStripe(stripe, fieldColor)
                    .padding(horizontal = 4.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy((-8).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { leadingButtons() }
                // 12dp above and below a 24sp line: one line is as high as the buttons.
                Box(Modifier.weight(1f).padding(start = 4.dp, top = 12.dp, bottom = 12.dp)) {
                    if (value.text.isEmpty()) {
                        CompositionLocalProvider(LocalContentColor provides placeholderColor) {
                            ProvideTextStyle(MaterialTheme.typography.bodyLarge) { placeholder() }
                        }
                    }
                    innerTextField()
                }
                if (trailing != null) {
                    Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) { trailing() }
                }
                sendButton()
            }
        },
    )
}

/** Filled once there is something to send, and squarer while pressed (Expressive). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SendButton(canSend: Boolean, waitLeft: Int, onSend: () -> Unit, modifier: Modifier = Modifier) {
    FilledIconButton(
        onClick = onSend,
        shapes = IconButtonDefaults.shapes(),
        enabled = canSend,
        // Spelled out: the defaults drew the arrow too faint to make out on the filled
        // button, and the empty one should look like the plain button it replaces.
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = modifier,
    ) {
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

private enum class InputPanel { RecentlySent, ChatModes }

/** Opens and closes the [RecentlySentPanel]. */
@Composable
private fun RecentlySentClock(open: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PanelToggle(open, enabled, onClick, modifier) {
        Icon(painterResource(R.drawable.ic_history), contentDescription = stringResource(R.string.recently_sent))
    }
}

/** Opens and closes a panel above the input; drawn in the accent color while it is open. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PanelToggle(
    open: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = modifier,
        content = icon,
    )
}

/**
 * Slow mode, followers-only and the switches Twitch gives a mod, each one tap away. A wait set by
 * command that Twitch's own list lacks still shows, selected, among the others.
 */
@Composable
private fun ChatModesPanel(modes: RoomState, onChange: (RoomState) -> Unit, onClose: () -> Unit) {
    InputPanelSurface(R.string.chat_modes, onClose) {
        ModeTitle(R.string.chat_modes_slow)
        ChoiceRow(
            choices = (listOf(0) + ChatModes.SLOW_SECONDS + modes.slow).distinct().sorted(),
            selected = modes.slow,
            label = { if (it == 0) stringResource(R.string.chat_modes_off) else "${it}s" },
            onSelect = { onChange(modes.copy(slow = it)) },
        )
        ModeTitle(R.string.chat_modes_followers)
        ChoiceRow(
            choices = (listOf(-1) + ChatModes.FOLLOW_MINUTES + modes.followersOnly).distinct().sorted(),
            selected = modes.followersOnly,
            label = {
                when (it) {
                    -1 -> stringResource(R.string.chat_modes_off)
                    0 -> stringResource(R.string.chat_modes_any_follower)
                    else -> formatMinutes(it)
                }
            },
            onSelect = { onChange(modes.copy(followersOnly = it)) },
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(top = 4.dp, bottom = 4.dp),
        ) {
            ModeChip(R.string.chat_modes_subs, modes.subsOnly) { onChange(modes.copy(subsOnly = it)) }
            ModeChip(R.string.mode_emotes, modes.emoteOnly) { onChange(modes.copy(emoteOnly = it)) }
            ModeChip(R.string.chat_modes_unique, modes.uniqueChat) { onChange(modes.copy(uniqueChat = it)) }
        }
    }
}

/**
 * A panel above the input with a handle and a [title]. Dragged down it follows the finger and
 * closes ([onClose]) once pulled far or flung; a short pull springs back.
 */
@Composable
private fun InputPanelSurface(title: Int, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var pulled by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val closeAfter = with(density) { 64.dp.toPx() }
    val closeFaster = with(density) { 800.dp.toPx() }
    val drag = rememberDraggableState { delta -> pulled = (pulled + delta).coerceAtLeast(0f) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            // Pulled down it sinks into its own edge instead of sliding under the input.
            .clipToBounds()
            // Before the layer, so the drag is measured where the finger is and not where the panel went.
            .draggable(
                state = drag,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    if (pulled > closeAfter || velocity > closeFaster) onClose()
                    // Through the drag state, so touching it again stops the spring at once.
                    else drag.drag {
                        var last = pulled
                        animate(pulled, 0f) { value, _ -> dragBy(value - last); last = value }
                    }
                },
            )
            .graphicsLayer {
                translationY = pulled
                alpha = 1f - (pulled / (closeAfter * 2)).coerceAtMost(0.6f)
            },
    ) {
        Column(Modifier.padding(bottom = 4.dp)) {
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    .align(Alignment.CenterHorizontally),
            )
            Text(
                stringResource(title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            content()
        }
    }
}

@Composable
private fun ModeTitle(res: Int) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
    )
}

/** One of several values as chips side by side, which scroll when they do not fit. */
@Composable
private fun ChoiceRow(choices: List<Int>, selected: Int, label: @Composable (Int) -> String, onSelect: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(choices, key = { it }) { choice ->
            FilterChip(
                selected = choice == selected,
                onClick = { if (choice != selected) onSelect(choice) },
                label = { Text(label(choice)) },
            )
        }
    }
}

@Composable
private fun ModeChip(label: Int, on: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = on,
        onClick = { onChange(!on) },
        label = { Text(stringResource(label)) },
        leadingIcon = if (on) {
            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else null,
    )
}

/** The user's last messages, one line each, to send one of them again. */
@Composable
private fun RecentlySentPanel(texts: List<String>, onPick: (String) -> Unit, onClose: () -> Unit) {
    InputPanelSurface(R.string.recently_sent, onClose) {
        if (texts.isEmpty()) {
            Text(
                stringResource(R.string.recently_sent_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        texts.forEach { text ->
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(text) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/** A combined chat's target channel as its picture, with the others a tap away. */
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
 * Places a menu right on top of its anchor. Material keeps menus 48dp from the window edges, which
 * would float the send picker's menu far above the input.
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
        // Grows out of the tapped picture's corner.
        transformOrigin = TransformOrigin(if (ltr) 0f else 1f, 1f)
        val x = if (ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            (anchorBounds.top - popupContentSize.height).coerceAtLeast(0),
        )
    }
}

/** A timeout's rest: "4:32", "1:04:32", or whole days for the long ones Twitch allows (up to 14). */
@Composable
private fun timeLeft(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = seconds / 60 % 60
    val secs = seconds % 60
    return when {
        hours >= 24 -> ((seconds + 86_399) / 86_400).toInt().let { pluralStringResource(R.plurals.timeout_days_left, it, it) }
        hours > 0 -> "%d:%02d:%02d".format(hours, minutes, secs)
        else -> "%d:%02d".format(minutes, secs)
    }
}

/** Shown instead of the placeholder while this restriction applies. */
private val ChatRestriction.hint: Int
    get() = when (this) {
        ChatRestriction.SubsOnly -> R.string.input_hint_subs_only
        ChatRestriction.FollowersOnly -> R.string.input_hint_followers_only
        ChatRestriction.EmoteOnly -> R.string.input_hint_emote_only
    }

@Composable
private fun SuggestionRow(suggestions: List<Suggestion>, imageLoader: ImageLoader, onClick: (Suggestion) -> Unit, bottomGap: Dp) {
    LazyRow(
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = bottomGap),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(suggestions, key = {
            when (it) {
                is Suggestion.EmoteSuggestion -> "e:" + it.emote.name
                is Suggestion.UserSuggestion -> "u:" + it.name
                is Suggestion.CommandSuggestion -> "c:" + it.name
                is Suggestion.EmojiSuggestion -> "j:" + it.emoji.value
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
                    is Suggestion.EmojiSuggestion -> {
                        Text(s.emoji.value, fontSize = 20.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(":${s.shortcode}:", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/**
 * Slides the reply up out from behind the input and back down, with Material 3's motion. It
 * leaves with the message it showed, not with an empty one.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AnimatedReply(replyTo: ChatItem?, content: @Composable (ChatItem) -> Unit) {
    val motion = MaterialTheme.motionScheme
    var shown by remember { mutableStateOf(replyTo) }
    if (replyTo != null) shown = replyTo
    AnimatedVisibility(
        visible = replyTo != null,
        enter = slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(motion.defaultEffectsSpec()),
        exit = slideOutVertically(motion.fastSpatialSpec()) { it } + fadeOut(motion.fastEffectsSpec()),
    ) {
        shown?.let { content(it) }
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
 * Who the message being written answers, as a card: the person's picture, "Replying to name" and
 * what they wrote. Two lines, so it takes barely more room than the strip it replaces; a tap shows
 * a longer message whole.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ReplyCard(item: ChatItem, pictureUrl: String?, imageLoader: ImageLoader, onCancel: (() -> Unit)?) {
    val motion = MaterialTheme.motionScheme
    val name = item.displayName ?: item.login.orEmpty()
    val said = stringResource(R.string.replying_to, name, item.text)
    val nameColor = readableNameColor(item.color, item.login, isAppInDarkTheme())
    val label = stringResource(R.string.reply_card_label)
    var expanded by remember(item.id) { mutableStateOf(false) }
    var cutOff by remember(item.id) { mutableStateOf(false) }
    // Top, not centred: opening a long message would otherwise move the picture and the X down.
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(
                enabled = cutOff || expanded,
                onClickLabel = stringResource(if (expanded) R.string.reply_card_collapse else R.string.reply_card_expand),
            ) { expanded = !expanded }
            .animateContentSize(motion.defaultSpatialSpec())
            .padding(start = 8.dp, end = if (onCancel != null) 0.dp else 12.dp),
    ) {
        Box(Modifier.padding(top = 8.dp)) {
            PersonAvatar(name = name, color = nameColor, pictureUrl = pictureUrl, imageLoader = imageLoader, size = 28.dp)
        }
        Column(Modifier.weight(1f).padding(vertical = 6.dp).semantics(mergeDescendants = true) { contentDescription = said }) {
            Text(
                buildAnnotatedString {
                    append("$label ")
                    withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.Bold)) { append(name) }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) cutOff = it.hasVisualOverflow },
            )
        }
        if (onCancel != null) IconButton(onClick = onCancel, modifier = Modifier.padding(top = 2.dp).size(40.dp)) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel), modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * The input area for guests: reading needs no account, writing does, so this explains that and
 * links to the login.
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
