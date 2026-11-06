package me.lesh.chatter.ui.inbox

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.lesh.chatter.R
import me.lesh.chatter.chat.InboxWhisper
import me.lesh.chatter.ui.FormSheet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import me.lesh.chatter.ui.theme.isAppInDarkTheme

/** Who a whisper conversation is with; enough to open one before anything was exchanged. */
data class WhisperTarget(val login: String, val userId: String?, val displayName: String)

/**
 * One whisper conversation, laid out like a messaging app: their whispers on the left, the user's
 * on the right, the newest at the bottom beside the input. Twitch keeps no whisper history to load,
 * so it holds what Chatter received and sent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WhisperChat(
    name: String,
    nameColor: Color,
    /** Newest first. */
    messages: List<InboxWhisper>,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    /** Sends, and returns null or the reason Twitch refused. */
    onSend: suspend (String) -> String?,
    onDelete: () -> Unit,
) {
    var deleting by rememberSaveable { mutableStateOf(false) }
    val background = chatBackground()
    Scaffold(
        containerColor = background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InitialAvatar(name, nameColor, size = 36.dp)
                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (messages.isNotEmpty()) {
                        IconButton(onClick = { deleting = true }) {
                            Icon(Icons.Outlined.Delete, stringResource(R.string.whisper_delete))
                        }
                    }
                },
            )
        },
        bottomBar = { WhisperInput(name, background, onSend, snackbar) },
    ) { padding ->
        if (messages.isEmpty()) {
            Box(Modifier.padding(padding)) {
                EmptyTab(
                    title = stringResource(R.string.whisper_chat_empty, name),
                    hint = stringResource(R.string.whisper_chat_empty_hint),
                ) { InitialAvatar(name, nameColor, size = EMPTY_PICTURE_SIZE) }
            }
        } else {
            Bubbles(messages, Modifier.padding(padding))
        }
    }

    if (deleting) {
        FormSheet(
            title = stringResource(R.string.whisper_delete_title, name),
            text = stringResource(R.string.inbox_clear_text),
            confirmLabel = stringResource(R.string.inbox_clear_confirm),
            onConfirm = onDelete,
            onDismiss = { deleting = false },
            destructive = true,
        )
    }
}

/**
 * The messages from the bottom up. Whispers from one side within a few minutes form a group: they
 * sit close, round off only on the outside and show the time once, under the last one.
 */
@Composable
private fun Bubbles(messages: List<InboxWhisper>, modifier: Modifier) {
    val zone = remember { ZoneId.systemDefault() }
    val today = LocalDate.now(zone)
    fun dayOf(w: InboxWhisper) = Instant.ofEpochMilli(w.timestamp).atZone(zone).toLocalDate()
    fun joined(older: InboxWhisper?, newer: InboxWhisper?) = older != null && newer != null &&
        older.outgoing == newer.outgoing && dayOf(older) == dayOf(newer) &&
        newer.timestamp - older.timestamp < GROUP_GAP_MS

    LazyColumn(
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        itemsIndexed(messages, key = { _, w -> w.id }) { i, whisper ->
            val newer = messages.getOrNull(i - 1)
            val older = messages.getOrNull(i + 1)
            val firstOfDay = older == null || dayOf(older) != dayOf(whisper)
            val joinsOlder = joined(older, whisper)
            val joinsNewer = joined(whisper, newer)
            Column(Modifier.animateItem().padding(top = if (joinsOlder) 2.dp else 10.dp)) {
                if (firstOfDay) {
                    Text(
                        dayLabel(dayOf(whisper), today),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 12.dp),
                    )
                }
                Bubble(whisper, joinsOlder = joinsOlder, joinsNewer = joinsNewer)
            }
        }
    }
}

@Composable
private fun Bubble(whisper: InboxWhisper, joinsOlder: Boolean, joinsNewer: Boolean) {
    val mine = whisper.outgoing
    val colors = MaterialTheme.colorScheme
    val top = if (joinsOlder) JOINED_CORNER else BUBBLE_CORNER
    val bottom = if (joinsNewer) JOINED_CORNER else BUBBLE_CORNER
    // Only the corners on the sender's side join up, like in a messaging app.
    val shape = if (mine) {
        RoundedCornerShape(topStart = BUBBLE_CORNER, topEnd = top, bottomEnd = bottom, bottomStart = BUBBLE_CORNER)
    } else {
        RoundedCornerShape(topStart = top, topEnd = BUBBLE_CORNER, bottomEnd = BUBBLE_CORNER, bottomStart = bottom)
    }
    val context = LocalContext.current
    Column(
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (mine) BUBBLE_INSET else 0.dp, end = if (mine) 0.dp else BUBBLE_INSET),
    ) {
        Surface(color = if (mine) ownBubbleColor() else colors.surfaceContainerHighest, shape = shape) {
            Text(
                whisper.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (mine) colors.onPrimaryContainer else colors.onSurface,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
        if (!joinsNewer) {
            Text(
                remember(whisper.timestamp) { DateFormat.getTimeFormat(context).format(Date(whisper.timestamp)) },
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 6.dp, end = 6.dp),
            )
        }
    }
}

/** Like the chat's own input: a pill on the background, with send beside it in reach of the thumb. */
@Composable
private fun WhisperInput(name: String, background: Color, onSend: suspend (String) -> String?, snackbar: SnackbarHostState) {
    var text by rememberSaveable { mutableStateOf("") }
    // Held from the tap until Twitch answers, so a second tap cannot send it twice.
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val canSend = text.isNotBlank() && !sending
    val send = {
        if (canSend) {
            sending = true
            scope.launch {
                val refused = onSend(text.trim())
                sending = false
                if (refused == null) text = "" else snackbar.showSnackbar(refused)
            }
        }
    }
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            maxLines = 5,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            decorationBox = { field ->
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier
                        .background(colors.surfaceContainerHighest, RoundedCornerShape(24.dp))
                        .padding(horizontal = 18.dp, vertical = 13.dp),
                ) {
                    if (text.isEmpty()) {
                        Text(
                            stringResource(R.string.whisper_reply_title, name),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    field()
                }
            },
            modifier = Modifier.weight(1f),
        )
        FilledIconButton(
            onClick = send,
            enabled = canSend,
            modifier = Modifier.padding(start = 8.dp).size(48.dp),
        ) {
            if (sending) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else {
                Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send))
            }
        }
    }
}

/**
 * White in light themes, where the grey of their bubbles would hardly stand off the usual light
 * grey; the usual background in dark ones, pure black included.
 */
@Composable
private fun chatBackground(): Color =
    if (isAppInDarkTheme()) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLowest

/** In light themes the accent container is a loud cyan or pink next to the greys, so it is toned down. */
@Composable
private fun ownBubbleColor(): Color {
    val colors = MaterialTheme.colorScheme
    return if (isAppInDarkTheme()) colors.primaryContainer else lerp(colors.surfaceContainerHighest, colors.primaryContainer, 0.6f)
}

/** Longer apart than this, two whispers from one side start a new group. */
private const val GROUP_GAP_MS = 5 * 60_000L
private val BUBBLE_CORNER = 20.dp
private val JOINED_CORNER = 4.dp

/** Room left on the other side, so a long whisper still reads as one side's. */
private val BUBBLE_INSET = 56.dp
