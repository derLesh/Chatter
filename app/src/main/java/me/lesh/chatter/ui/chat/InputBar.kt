package me.lesh.chatter.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
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
import me.lesh.chatter.chat.ArgumentHint
import me.lesh.chatter.chat.Bot
import me.lesh.chatter.chat.BotSuggestion
import me.lesh.chatter.chat.UsageForm
import me.lesh.chatter.chat.ChatItem
import me.lesh.chatter.chat.ChatModes
import me.lesh.chatter.chat.ChatRestriction
import me.lesh.chatter.chat.Penalty
import me.lesh.chatter.chat.RoomState
import me.lesh.chatter.chat.SendLimits
import me.lesh.chatter.settings.SendLaterButton
import me.lesh.chatter.ui.Suggestion
import me.lesh.chatter.ui.channels.ChannelAvatar
import me.lesh.chatter.ui.channels.formatMinutes
import me.lesh.chatter.ui.theme.readableNameColor
import me.lesh.chatter.ui.theme.isAppInDarkTheme
import me.lesh.chatter.ui.inbox.PersonAvatar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size

/**
 * The message input with emote picker, suggestions and reply strip.
 *
 * [emotePicker] opens in place of the keyboard, below the input and as high as the keyboard was,
 * so the message stays in sight while picking; the emote button then brings the keyboard back.
 * Without it the button does nothing, as in a preview.
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
 * While the input is empty three dots in it open a menu over the chat, growing out of them. It
 * holds the user's last messages ([recentlySent]), of which a tap puts one back into the input
 * ([onReuseSent]); with [onPickPicture] picking a picture to send; and given [chatModes], those of
 * the channel the input sends to when the user moderates it, the chat modes, a change to which
 * goes to [onChatModes] as the wished state. The last messages and the chat modes open as a panel
 * above the input in the menu's place. A tap anywhere else closes the menu, and unlike the panels
 * it cannot be pulled down. Without [menuOverChat] the menu pushes up what is above, for a place
 * that would cut off what lies outside the input.
 *
 * With [sendLater] a message can be planned to go out later, and the planned ones show above the
 * input.
 *
 * With [suggestionsOverChat] the suggestions take no room: they slide up above the input, over
 * the chat, so a chat that makes room for the input does not move while typing.
 *
 * [attachedLength] is what the message gets on top of the typed text, like a picture's link: it
 * counts towards Twitch's limit, and the message may go out without text.
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
    emotePicker: (@Composable (Modifier) -> Unit)?,
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
    chatModes: RoomState? = null,
    onChatModes: (RoomState) -> Unit = {},
    sendLater: SendLater? = null,
    buildStripe: Boolean = false,
    replyCard: Boolean = false,
    replyPictureUrl: String? = null,
    onCoveredHeight: ((Int) -> Unit)? = null,
    suggestionsOverChat: Boolean = false,
    menuOverChat: Boolean = true,
    onPickPicture: (() -> Unit)? = null,
    attachedLength: Int = 0,
    argumentHint: ArgumentHint? = null,
) {
    val stripe = buildStripe && BuildStripe.offered
    val choosing = sendChannels.size > 1 && sendChannel != null
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var emotesOpen by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) emotesOpen = false }
    NavigationBackHandler(
        state = rememberNavigationEventState(currentInfo = NavigationEventInfo.None),
        isBackEnabled = emotesOpen,
        onBackCompleted = { emotesOpen = false },
    )
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
    val length = remember(value.text, attachedLength) { SendLimits.length(value.text) + attachedLength }
    val tooLong = length > SendLimits.MAX_LENGTH
    val canSend = enabled && (value.text.isNotBlank() || attachedLength > 0) && !tooLong && waitLeft == 0 && timeoutLeft == 0L
    // Slow mode and a timeout end, so a message can be planned for after them.
    val canPlan = sendLater?.button != null && enabled && value.text.isNotBlank() && !tooLong
    var planning by rememberSaveable { mutableStateOf(false) }
    if (planning) {
        SendLaterSheet(value.text.trim(), onPlan = { sendLater?.onPlan(it) }, onDismiss = { planning = false })
    }
    // Something attached counts as typed: the field is then about that message.
    val offersMenu = value.text.isEmpty() && attachedLength == 0
    val scope = rememberCoroutineScope()
    // One panel at a time above the input, so the chat keeps its room.
    var openPanel by remember { mutableStateOf<InputPanel?>(null) }
    val menuOpen = openPanel == InputPanel.Menu
    val menuAnchor = remember { MenuAnchor() }
    var menuOrigin by remember { mutableStateOf(Offset.Unspecified) }
    // Not state: counts the menu's openings, so a list still loading for an earlier one is dropped.
    val menuOpenings = remember { intArrayOf(0) }
    val recentOpen = openPanel == InputPanel.RecentlySent
    val modesOpen = openPanel == InputPanel.ChatModes
    var recent by remember { mutableStateOf(emptyList<String>()) }
    val openRecent = {
        // Loaded before opening, so the panel unfolds at its final height; unless the menu was
        // closed meanwhile.
        val opening = menuOpenings[0]
        scope.launch {
            val loaded = recentlySent()
            if (openPanel == InputPanel.Menu && menuOpenings[0] == opening) {
                recent = loaded
                openPanel = InputPanel.RecentlySent
            }
        }
        Unit
    }
    // Typing takes the dots away, and what the panels show belongs to the channel the input sends to.
    LaunchedEffect(offersMenu, enabled) {
        if (openPanel != null && (!offersMenu || !enabled)) openPanel = null
    }
    LaunchedEffect(sendChannel) { openPanel = null }
    val offersModes = chatModes != null
    LaunchedEffect(offersModes) {
        if (modesOpen && !offersModes) openPanel = null
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
    Column(
        modifier
            .then(covered)
            .onGloballyPositioned { menuAnchor.input = it }
            // Watched, not taken: the tap still does what it does, and the dots toggle on their own.
            // Only while the menu is open, since a watcher still takes the touches around a floating
            // input from the chat below.
            .then(
                if (!menuOpen) Modifier else Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (!menuAnchor.onMenuOrDots(down.position)) openPanel = null
                    }
                },
            ),
    ) {
        // A tap on the chat only closes the menu, as a tap beside a menu does.
        if (menuOpen && menuOverChat) {
            Box(
                Modifier
                    .zIndex(0.5f)
                    .coveringAbove(LocalWindowInfo.current.containerSize.height)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown().consume()
                            openPanel = null
                        }
                    },
            )
        }
        GrowingFromDots(menuOpen, menuOrigin, menuOverChat) {
            // Its entries still take taps while it shrinks away, so each checks it is still open.
            Box(Modifier.onGloballyPositioned { menuAnchor.menu = it }) {
                InputMenu(
                    onPickPicture = onPickPicture?.let { pick ->
                        { if (openPanel == InputPanel.Menu) { openPanel = null; pick() } }
                    },
                    onRecentlySent = { if (openPanel == InputPanel.Menu) openRecent() },
                    onChatModes = { if (openPanel == InputPanel.Menu) openPanel = InputPanel.ChatModes }.takeIf { offersModes },
                )
            }
        }
        if (suggestionsOverChat) {
            val above = (argumentHint to suggestions).takeIf { argumentHint != null || suggestions.isNotEmpty() }
            RisingFromInput(above, Modifier.takingNoHeight()) { (hint, shown) ->
                Column {
                    hint?.let { ArgumentHintBar(it) }
                    if (shown.isNotEmpty()) SuggestionRow(shown, imageLoader, onSuggestion, bottomGap = 4.dp)
                    else Spacer(Modifier.height(4.dp))
                }
            }
        } else {
            argumentHint?.let { ArgumentHintBar(it) }
            // The field keeps its own gap above, as wide as the one below it.
            if (suggestions.isNotEmpty()) SuggestionRow(suggestions, imageLoader, onSuggestion, bottomGap = 0.dp)
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
            RisingFromInput(replyTo) { item ->
                if (replyCard) ReplyCard(item, replyPictureUrl, imageLoader, onCancelReply)
                else ReplyBar(item, onCancelReply)
            }
        }
        val leadingButtons = @Composable {
            if (choosing) {
                SendChannelPicker(sendChannels, sendChannel, channelInfo, imageLoader, enabled, onSendChannel)
            }
            if (emotesOpen) {
                IconButton(onClick = { focus.requestFocus(); keyboard?.show() }, shapes = IconButtonDefaults.shapes()) {
                    Icon(painterResource(R.drawable.ic_keyboard), contentDescription = stringResource(R.string.input_keyboard))
                }
            } else {
                IconButton(
                    onClick = {
                        if (emotePicker != null) {
                            keyboard?.hide()
                            emotesOpen = true
                        }
                    },
                    shapes = IconButtonDefaults.shapes(),
                    enabled = enabled,
                ) {
                    Icon(Icons.Default.Face, contentDescription = stringResource(R.string.emotes))
                }
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
        val toggleMenu = {
            if (openPanel == null) {
                menuOpenings[0]++
                menuOrigin = menuAnchor.dotsCenter()
            }
            openPanel = if (openPanel == null) InputPanel.Menu else null
        }
        val trailing: (@Composable () -> Unit)? = if (offersMenu) {
            { MenuDots(openPanel != null, enabled, toggleMenu, Modifier.onGloballyPositioned { menuAnchor.dots = it }) }
        } else if (timeoutLeft > 0 && value.text.isNotEmpty()) {
            // The placeholder is gone once something is typed; the timeout stays in sight here.
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
            val hold = canPlan && sendLater?.button == SendLaterButton.HoldSend
            if (canPlan && sendLater?.button == SendLaterButton.OwnButton) {
                Row(
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
        if (emotePicker != null) EmotePanel(emotesOpen, onKeyboardBack = { emotesOpen = false }, content = emotePicker)
    }
}

/**
 * How high the keyboard stood above the navigation bar the last time it was up, in px, upright and
 * on its side; 0 until then. Shared, since every input sits on the same keyboard, and kept across
 * starts by [me.lesh.chatter.AppContainer].
 */
object KeyboardHeight {
    var upright by mutableIntStateOf(0)
        private set
    var sideways by mutableIntStateOf(0)
        private set

    /** Takes the heights saved last time, unless the keyboard was up since the start. */
    fun restore(saved: Pair<Int, Int>) {
        if (upright == 0) upright = saved.first
        if (sideways == 0) sideways = saved.second
    }

    fun of(sideways: Boolean): Int = if (sideways) this.sideways else upright

    fun record(sideways: Boolean, px: Int) {
        if (sideways) this.sideways = px else upright = px
    }
}

/**
 * The share of the window the emote panel takes until the keyboard has been up once: about what a
 * keyboard takes upright, and on its side.
 */
private const val PANEL_SHARE_UPRIGHT = 0.35f
private const val PANEL_SHARE_SIDEWAYS = 0.5f

/**
 * The emote picker where the keyboard was. It takes exactly the room the keyboard gives up, so the
 * input above stays put: it grows while the keyboard slides down and shrinks under it when the
 * keyboard comes back (its button, or a tap in the input), and then calls [onKeyboardBack].
 * Opened without a keyboard it slides up on its own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmotePanel(open: Boolean, onKeyboardBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    val ime = WindowInsets.ime
    val imeTarget = WindowInsets.imeAnimationTarget
    val navigationBar = WindowInsets.navigationBars
    val density = LocalDensity.current
    val window = LocalWindowInfo.current
    fun sideways() = window.containerSize.run { width > height }
    // What the keyboard lifts the input by: the screen edge padding already covers the bar.
    fun lift(insets: WindowInsets) = (insets.getBottom(density) - navigationBar.getBottom(density)).coerceAtLeast(0)
    LaunchedEffect(Unit) {
        snapshotFlow { lift(imeTarget) }.collect { if (it > 0) KeyboardHeight.record(sideways(), it) }
    }
    val reveal = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open) {
            shown = true
            launch { if (lift(ime) > 0) reveal.snapTo(1f) else reveal.animateTo(1f) }
            // The keyboard is on its way down now; it counts as back once it heads up again
            // and has arrived.
            snapshotFlow { lift(imeTarget) }.dropWhile { it > 0 }.first { it > 0 }
            snapshotFlow { lift(imeTarget).let { target -> target > 0 && lift(ime) >= target } }.first { it }
            onKeyboardBack()
        } else {
            if (lift(ime) == 0) reveal.animateTo(0f) else reveal.snapTo(0f)
            shown = false
        }
    }
    if (!shown) return
    val color = MaterialTheme.colorScheme.surfaceContainerLow
    Box(
        Modifier
            .fillMaxWidth()
            // The color runs on behind the navigation bar, as the keyboard's does.
            .drawBehind {
                if (size.height > 0) drawRect(color, size = Size(size.width, size.height + navigationBar.getBottom(this)))
            }
            .clipToBounds()
            // Insets read while measuring, so the keyboard's slide moves the panel without
            // composing the picker again every frame.
            .layout { measurable, constraints ->
                val full = KeyboardHeight.of(sideways()).takeIf { it > 0 }
                    ?: (window.containerSize.height * if (sideways()) PANEL_SHARE_SIDEWAYS else PANEL_SHARE_UPRIGHT).roundToInt()
                val height = ((full - lift(ime)).coerceAtLeast(0) * reveal.value).roundToInt()
                val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, full))
                layout(constraints.maxWidth, height) { placeable.place(0, 0) }
            },
    ) {
        content(Modifier.fillMaxSize())
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

private enum class InputPanel { Menu, RecentlySent, ChatModes }

/** Opens the [InputMenu], and closes it or the panel picked from it. */
@Composable
private fun MenuDots(open: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PanelToggle(open, enabled, onClick, modifier) {
        Icon(Icons.Default.MoreVert, contentDescription = stringResource(if (open) R.string.input_menu_close else R.string.input_menu))
    }
}

/** Where the dots sit across the input, so the menu can grow out of them. Read on a tap, not state. */
private class MenuAnchor {
    var input: LayoutCoordinates? = null
    var dots: LayoutCoordinates? = null
    var menu: LayoutCoordinates? = null

    /**
     * Whether [position], from the input's top left corner, lies on the dots or on the menu, as far
     * as it has grown: while growing it still lies partly over the input.
     */
    fun onMenuOrDots(position: Offset): Boolean {
        val input = input?.takeIf { it.isAttached } ?: return false
        return listOfNotNull(dots, menu).any { it.isAttached && input.localBoundingBoxOf(it).contains(position) }
    }

    /** The dots' middle from the input's top left corner, in px; unspecified while either is not laid out. */
    fun dotsCenter(): Offset {
        val input = input?.takeIf { it.isAttached } ?: return Offset.Unspecified
        val dots = dots?.takeIf { it.isAttached } ?: return Offset.Unspecified
        return input.localPositionOf(dots, Offset(dots.size.width / 2f, dots.size.height / 2f))
    }
}

/**
 * The menu growing out of the dots and back into them. [dots] is their middle from the input's top
 * left corner, below which the menu ends, so a reply or planned messages between the two are
 * allowed for. Over the chat it takes no room; otherwise it also unfolds, so what is above moves
 * along.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GrowingFromDots(visible: Boolean, dots: Offset, overChat: Boolean, content: @Composable () -> Unit) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        // On top of the reply and the field below it, where it starts out from the dots.
        modifier = Modifier.zIndex(1f).then(if (overChat) Modifier.takingNoHeight() else Modifier),
        enter = fadeIn(motion.defaultEffectsSpec()) +
            if (overChat) EnterTransition.None else expandVertically(motion.defaultSpatialSpec(), clip = false),
        exit = fadeOut(motion.fastEffectsSpec()) +
            if (overChat) ExitTransition.None else shrinkVertically(motion.fastSpatialSpec(), clip = false),
    ) {
        val scale by transition.animateFloat(
            transitionSpec = { if (targetState == EnterExitState.Visible) motion.defaultSpatialSpec() else motion.fastSpatialSpec() },
        ) { if (it == EnterExitState.Visible) 1f else 0f }
        // Scaled here rather than with scaleIn, whose pivot is a share of a size not known before
        // the menu is laid out, while the dots lie a set distance below it.
        Box(
            Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = if (dots.isUnspecified || size.width == 0f || size.height == 0f) TransformOrigin(1f, 1f)
                else TransformOrigin(dots.x / size.width, 1f + dots.y / size.height)
            },
        ) { content() }
    }
}

/** What the input does besides typing, side by side as round buttons; left out when not given. */
@Composable
private fun InputMenu(
    onPickPicture: (() -> Unit)?,
    onRecentlySent: () -> Unit,
    onChatModes: (() -> Unit)?,
) {
    InputPanelSurface(title = null, onClose = null) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            if (onPickPicture != null) MenuEntry(R.drawable.ic_image, R.string.input_pick_picture, onPickPicture, Modifier.weight(1f, fill = false))
            MenuEntry(R.drawable.ic_history, R.string.recently_sent, onRecentlySent, Modifier.weight(1f, fill = false))
            if (onChatModes != null) MenuEntry(R.drawable.ic_shield, R.string.chat_modes, onChatModes, Modifier.weight(1f, fill = false))
        }
    }
}

/**
 * One of the menu's round buttons. Given a weight they share a narrow window's width, which caps
 * the width here, instead of pushing the last one out.
 */
@Composable
private fun MenuEntry(icon: Int, label: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(88.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
        )
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
 * A panel above the input with, if given, a [title]. With [onClose] it has a handle: dragged down
 * it follows the finger and closes once pulled far or flung; a short pull springs back.
 */
@Composable
private fun InputPanelSurface(title: Int?, onClose: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    var pulled by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val closeAfter = with(density) { 64.dp.toPx() }
    val closeFaster = with(density) { 800.dp.toPx() }
    // Without onClose the drag is still taken, only to go nowhere: in the conversation's sheet it
    // would pull the whole sheet down.
    val drag = rememberDraggableState { delta -> if (onClose != null) pulled = (pulled + delta).coerceAtLeast(0f) }
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
                    if (pulled > closeAfter || velocity > closeFaster) onClose?.invoke()
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
            if (onClose != null) {
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .size(width = 32.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        .align(Alignment.CenterHorizontally),
                )
            }
            if (title != null) {
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            } else {
                Spacer(Modifier.height(if (onClose != null) 4.dp else 8.dp))
            }
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
    // New suggestions start at the front: the list would keep the chip it showed first and hide
    // the best ones, which come before it.
    val state = remember(suggestions) { LazyListState() }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = bottomGap),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(suggestions, key = {
            when (it) {
                is Suggestion.EmoteSuggestion -> "e:" + it.emote.name
                is Suggestion.UserSuggestion -> "u:" + it.name
                is Suggestion.CommandSuggestion -> "c:" + it.name
                is Suggestion.BotCommandSuggestion -> "b:" + it.command.typed.lowercase()
                is Suggestion.ArgumentSuggestion -> "a:" + it.value
                is Suggestion.EmojiSuggestion -> "j:" + it.emoji.value
            }
        }) { s ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .heightIn(min = 36.dp)
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
                    is Suggestion.BotCommandSuggestion -> BotCommandChip(s.command)
                    is Suggestion.ArgumentSuggestion -> Text(s.value, style = MaterialTheme.typography.bodyMedium)
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

fun botName(bot: Bot): Int = when (bot) {
    Bot.Supibot -> R.string.bot_supibot
    Bot.PotatBot -> R.string.bot_potatbot
    Bot.ChronophylosBot -> R.string.bot_chronophylosbot
    Bot.Fossabot -> R.string.bot_fossabot
    Bot.Moobot -> R.string.bot_moobot
    Bot.Nightbot -> R.string.bot_nightbot
    Bot.StreamElements -> R.string.bot_streamelements
    Bot.Streamlabs -> R.string.bot_streamlabs
}

/**
 * A bot command with what follows it and, below, what it does or answers, cut short so the chip
 * stays small.
 */
@Composable
private fun BotCommandChip(command: BotSuggestion) {
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.widthIn(max = 220.dp).padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildAnnotatedString {
                    append(command.typed)
                    if (command.arguments.isNotEmpty()) withStyle(SpanStyle(color = faint)) { append(" " + command.arguments) }
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                " \u00B7 ${stringResource(botName(command.bot))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (command.description.isNotBlank()) {
            Text(
                command.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * How a bot command is written, with the argument the cursor is in picked out, and what the
 * command does. Several ways of writing it are split by "|", three at most; a tap opens all of
 * them, one per line, with the whole description.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ArgumentHintBar(hint: ArgumentHint) {
    val motion = MaterialTheme.motionScheme
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    val current = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    fun AnnotatedString.Builder.appendForm(form: UsageForm) {
        append(hint.command)
        form.arguments.forEachIndexed { index, argument ->
            append(" ")
            withStyle(if (index == form.current) current else SpanStyle(color = faint)) { append(argument.shown) }
        }
    }
    // Per command, so the next keystroke keeps it open.
    var open by remember(hint.command.lowercase()) { mutableStateOf(false) }
    var cutOff by remember(hint.command.lowercase()) { mutableStateOf(false) }
    val more = cutOff || hint.forms.size > MAX_FORMS_SHOWN
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(
                enabled = more || open,
                onClickLabel = stringResource(if (open) R.string.bot_hint_collapse else R.string.bot_hint_expand),
            ) { open = !open }
            .animateContentSize(motion.defaultSpatialSpec())
            .heightIn(max = 280.dp)
            .verticalScroll(rememberScrollState(), enabled = open)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (open) {
            // One line each, so every way of writing it can be read.
            hint.forms.forEach { form ->
                Text(buildAnnotatedString { appendForm(form) }, style = MaterialTheme.typography.bodyMedium)
            }
            if (hint.forms.isEmpty()) Text(hint.command, style = MaterialTheme.typography.bodyMedium)
        } else {
            val usage = buildAnnotatedString {
                hint.forms.take(MAX_FORMS_SHOWN).forEachIndexed { i, form ->
                    if (i > 0) withStyle(SpanStyle(color = faint)) { append("  |  ") }
                    appendForm(form)
                }
                if (hint.forms.isEmpty()) append(hint.command)
                if (hint.forms.size > MAX_FORMS_SHOWN) withStyle(SpanStyle(color = faint)) { append("  |  …") }
            }
            Text(
                usage,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (it.hasVisualOverflow) cutOff = true },
            )
        }
        if (hint.description.isNotBlank()) {
            Text(
                hint.description,
                style = MaterialTheme.typography.labelSmall,
                color = faint,
                maxLines = if (open) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!open && it.hasVisualOverflow) cutOff = true },
                modifier = Modifier.padding(top = if (open) 4.dp else 0.dp),
            )
        }
    }
}

private const val MAX_FORMS_SHOWN = 3

/** Like [takingNoHeight], [height] px high: as high as the window, it covers whatever is above. */
private fun Modifier.coveringAbove(height: Int): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, height))
    layout(constraints.maxWidth, 0) { placeable.place(0, -height) }
}

/**
 * Reports no height and places the content right above where it would be, so it lies over what is
 * above instead of pushing it away. Taps still reach it, as long as nothing above clips.
 */
private fun Modifier.takingNoHeight(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0))
    layout(placeable.width, 0) { placeable.place(0, -placeable.height) }
}

/**
 * Slides [value] up out from behind the input while there is one, and back down, with Material 3's
 * motion. It leaves showing what it showed last, not empty.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T : Any> RisingFromInput(value: T?, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val motion = MaterialTheme.motionScheme
    var shown by remember { mutableStateOf(value) }
    if (value != null) shown = value
    AnimatedVisibility(
        visible = value != null,
        modifier = modifier,
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
    val nameColor = readableNameColor(item.color, item.login, isAppInDarkTheme(), background = MaterialTheme.colorScheme.surfaceContainerHigh)
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
