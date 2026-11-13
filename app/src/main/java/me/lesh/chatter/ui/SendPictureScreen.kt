package me.lesh.chatter.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import me.lesh.chatter.R
import me.lesh.chatter.auth.AuthState
import me.lesh.chatter.irc.ConnectionState
import me.lesh.chatter.ui.channels.AddChannelSheet
import me.lesh.chatter.ui.channels.ChannelAvatar
import me.lesh.chatter.ui.chat.EmotePickerSheet
import me.lesh.chatter.ui.chat.InputBar
import me.lesh.chatter.upload.UploadTarget

/** Room the message keeps for a link not uploaded yet; kappa.lol's are about 25 characters. */
private const val LINK_ROOM = 40

/**
 * Sends one picture, shared from another app or picked in the input: the picture fills the
 * screen, and the channel, the upload site and the chat's own input lie over its lower edge. What
 * the upload is doing shows in a pill, as the chat shows its connection.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SendPictureScreen(vm: MainViewModel) {
    val sender = vm.picture
    val picture = sender.picture ?: return
    val auth by vm.authState.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val channelsLoaded by vm.channelsLoaded.collectAsStateWithLifecycle()
    val info by vm.channelInfo.collectAsStateWithLifecycle()
    val targets = vm.uploaderSetup.collectAsStateWithLifecycle().value.targets
    val connection by vm.connection.collectAsStateWithLifecycle()
    var choosingChannel by rememberSaveable { mutableStateOf(false) }
    var choosingUploader by rememberSaveable { mutableStateOf(false) }
    var addingChannel by rememberSaveable { mutableStateOf(false) }
    var pickingEmote by rememberSaveable { mutableStateOf(false) }

    // A channel closed meanwhile, or none known yet when the picture came in on a cold start.
    LaunchedEffect(channels, sender.channel) {
        if (sender.channel !in channels) channels.firstOrNull()?.let(sender::selectChannel)
    }
    // A key changed under Settings, or the site was removed there meanwhile.
    LaunchedEffect(targets) {
        val now = targets.firstOrNull { it.id == sender.uploader?.id } ?: targets.firstOrNull()
        if (now != null && now != sender.uploader) sender.selectUploader(now)
    }
    BackHandler { if (sender.isBusy) sender.cancel() else sender.close() }

    // No Surface around the picture, so the input's icons get their color here as in the chat.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AsyncImage(
            model = picture.file,
            contentDescription = null,
            imageLoader = vm.imageLoader,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        // Darker at both edges, so the bar and the input read on any picture.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.5f),
                    0.18f to Color.Transparent,
                    0.55f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.75f),
                ),
            ),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = { if (sender.isBusy) sender.cancel() else sender.close() }) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.picture_close), tint = Color.White)
            }
            Text(
                stringResource(R.string.picture_title),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            IconButton(onClick = vm::openUploaderSettings) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.picture_uploader_settings), tint = Color.White)
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // One column on wide screens too, as wide as a phone.
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        ) {
            when {
                auth == AuthState.Guest -> Gate(R.string.picture_log_in, R.string.guest_log_in, vm::leaveGuest)
                channelsLoaded && channels.isEmpty() -> Gate(R.string.picture_no_channel, R.string.picture_open_channel) { addingChannel = true }
                else -> {
                    UploadPill(sender.step, sender.uploader?.name.orEmpty())
                    if (sender.step == PictureStep.Ready) {
                        Text(
                            stringResource(R.string.picture_public),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f),
                            textAlign = TextAlign.Center,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
                        val channel = sender.channel
                        ChoicePill(
                            enabled = !sender.isBusy,
                            onClick = { choosingChannel = true },
                            icon = { ChannelAvatar(info[channel], vm.imageLoader, 24.dp, showLive = false) },
                            text = channel?.let { info[it]?.displayName ?: it } ?: "",
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        val uploaded = sender.uploaded
                        ChoicePill(
                            // Once online, the picture stays where it is; the pill shows its link.
                            enabled = !sender.isBusy && uploaded == null,
                            onClick = { choosingUploader = true },
                            icon = { UploaderIcon(sender.uploader, 24.dp) },
                            text = uploaded?.link?.removePrefix("https://") ?: sender.uploader?.name.orEmpty(),
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    InputBar(
                        value = sender.text,
                        onValueChange = { sender.text = it },
                        // Offline the picture would be uploaded for a message that cannot go out.
                        enabled = sender.channel != null && !sender.isBusy && connection == ConnectionState.Connected,
                        replyTo = null,
                        suggestions = emptyList(),
                        imageLoader = vm.imageLoader,
                        onSuggestion = {},
                        onCancelReply = null,
                        onEmotePicker = { pickingEmote = true },
                        onSend = sender::send,
                        recentlySent = { emptyList() },
                        onReuseSent = {},
                        attachedLength = (sender.uploaded?.link?.length ?: LINK_ROOM) + 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    }

    if (choosingChannel) {
        PickSheet(
            title = stringResource(R.string.picture_send_to),
            options = channels,
            selected = sender.channel,
            label = { info[it]?.displayName ?: it },
            icon = { ChannelAvatar(info[it], vm.imageLoader, 28.dp, showLive = false) },
            onPick = sender::selectChannel,
            onDismiss = { choosingChannel = false },
        )
    }
    if (choosingUploader) {
        PickSheet(
            title = stringResource(R.string.picture_uploader),
            hint = stringResource(R.string.picture_uploader_once),
            options = targets,
            selected = sender.uploader,
            label = { it.name },
            icon = { UploaderIcon(it, 28.dp) },
            onPick = sender::selectUploader,
            onDismiss = { choosingUploader = false },
            footer = { close ->
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { close(vm::openUploaderSettings) }
                        .padding(vertical = 12.dp, horizontal = 12.dp),
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text(
                        stringResource(R.string.picture_uploader_settings),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
        )
    }
    if (addingChannel) {
        AddChannelSheet(
            search = vm::searchChannels,
            imageLoader = vm.imageLoader,
            onAdd = vm::addChannel,
            onDismiss = { addingChannel = false },
        )
    }
    if (pickingEmote) {
        val emoteVersion by vm.emoteVersion.collectAsStateWithLifecycle()
        val emotes = remember(sender.channel, emoteVersion) { vm.emotesFor(sender.channel) }
        val emoji by produceState(emptyList()) { value = vm.emoji() }
        val owners = remember(emoteVersion) { vm.emoteOwners() }
        EmotePickerSheet(
            emotes = emotes,
            owners = owners,
            emoji = emoji,
            recent = vm.recentEmotes.collectAsStateWithLifecycle().value,
            imageLoader = vm.imageLoader,
            onPick = { vm.insertInto(sender, it.name) },
            onPickEmoji = { vm.insertInto(sender, it.value) },
            onReload = { vm.reloadEmotes(sender.channel) },
            onDismiss = { pickingEmote = false },
        )
    }
}

/** What stands in for the input when nothing can be sent yet, with the step that changes that. */
@Composable
private fun Gate(text: Int, action: Int, onAction: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = onAction) { Text(stringResource(action)) }
        }
    }
}

/**
 * The upload's progress or trouble, in the pill the chat uses for its connection: the loading
 * indicator while busy, a red warning sign once something went wrong.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UploadPill(step: PictureStep, site: String) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = step != PictureStep.Ready,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
    ) {
        // Kept while the pill slides away, so it leaves with the text it had.
        val last = remember { arrayOf(step) }
        if (step != PictureStep.Ready) last[0] = step
        val shown = last[0]
        val warning = shown is PictureStep.UploadFailed || shown == PictureStep.NotSent
        Surface(
            shape = CircleShape,
            color = colors.secondaryContainer,
            contentColor = colors.onSecondaryContainer,
            shadowElevation = 3.dp,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Crossfade(warning, animationSpec = motion.defaultEffectsSpec(), label = "upload pill icon") { warns ->
                    if (warns) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.error, modifier = Modifier.padding(4.dp).size(20.dp))
                    } else {
                        LoadingIndicator(Modifier.size(28.dp), color = LocalContentColor.current)
                    }
                }
                Spacer(Modifier.width(4.dp))
                AnimatedContent(
                    pillText(shown, site),
                    transitionSpec = {
                        (slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(motion.defaultEffectsSpec()))
                            .togetherWith(slideOutVertically(motion.defaultSpatialSpec()) { -it } + fadeOut(motion.fastEffectsSpec()))
                            .using(SizeTransform { _, _ -> motion.defaultSpatialSpec() })
                    },
                    contentAlignment = Alignment.CenterStart,
                    label = "upload status",
                ) { text -> Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun pillText(step: PictureStep, site: String): String = when (step) {
    PictureStep.Ready -> ""
    // In steps of five, so the text does not slide in anew for every percent.
    is PictureStep.Uploading -> stringResource(R.string.picture_uploading, (step.progress * 20).toInt() * 5)
    PictureStep.Sending -> stringResource(R.string.picture_sending)
    PictureStep.NotSent -> stringResource(R.string.picture_not_sent)
    is PictureStep.UploadFailed -> when (step.reason) {
        UploadFailure.NoConnection -> stringResource(R.string.picture_failed_connection, site)
        UploadFailure.SiteError -> stringResource(R.string.picture_failed_site, site)
        UploadFailure.TooLarge -> stringResource(R.string.picture_failed_too_large)
    }
}

/** A small choice over the picture: what it is set to, and a tap to change it. */
@Composable
private fun ChoicePill(
    enabled: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
        // Spelled out: with the see-through color Material cannot find the matching text color.
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, end = if (enabled) 6.dp else 12.dp, top = 4.dp, bottom = 4.dp),
        ) {
            icon()
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (enabled) Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

/** The site's favicon, shipped with the app; a site of the user's own gets its first letter. */
@Composable
internal fun UploaderIcon(target: UploadTarget?, size: Dp) = UploaderIcon(target?.favicon, target?.name, size)

/** [favicon], or the first letter of [name] where there is none. */
@Composable
internal fun UploaderIcon(favicon: Int?, name: String?, size: Dp) {
    if (favicon != null) {
        Image(
            painterResource(favicon),
            contentDescription = null,
            modifier = Modifier.size(size).clip(CircleShape).background(Color.White).padding(size / 8),
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Text(
                name?.firstOrNull()?.uppercase() ?: "⋯",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/**
 * A sheet of options, one row each with a radio button and a picture. Picking one closes it.
 * [footer] gets `close`, which slides the sheet away before running its action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> PickSheet(
    title: String,
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    icon: @Composable (T) -> Unit,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    hint: String? = null,
    footer: (@Composable (close: (() -> Unit) -> Unit) -> Unit)? = null,
) {
    val sheet = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val close = { action: () -> Unit ->
        scope.launch { sheet.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
        Unit
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
            }
            options.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { close { onPick(option) } }
                        .padding(vertical = 4.dp),
                ) {
                    RadioButton(selected = option == selected, onClick = { close { onPick(option) } })
                    icon(option)
                    Spacer(Modifier.width(12.dp))
                    Text(label(option), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            footer?.invoke(close)
        }
    }
}
