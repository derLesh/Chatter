package me.lesh.chatter.ui.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.Flow
import me.lesh.chatter.R
import me.lesh.chatter.chat.Segment
import me.lesh.chatter.emotes.Emote
import me.lesh.chatter.emotes.EmoteAccess
import me.lesh.chatter.emotes.EmoteProvider
import me.lesh.chatter.emotes.EmoteSetChoice
import me.lesh.chatter.emotes.label
import me.lesh.chatter.ui.settings.Provider

/** An emote tapped in a message, and the [channel] login that message is in. */
data class EmoteTap(val seg: Segment.EmoteSeg, val channel: String)

/**
 * Changing a channel's 7TV emotes from the emote card. [add], [rename] and [remove] return at once;
 * how it went shows as a snackbar, and a missing 7TV login is asked for there.
 */
interface EmoteEditing {
    /** Changes whenever what [access] says may have changed. */
    val revision: Flow<Any>

    /**
     * What the card may offer for the emote shown as [shownName]; null if the user may not change
     * [channel]'s 7TV emotes. [fresh] asks 7TV rather than its cache, once when the card opens.
     */
    suspend fun access(channel: String, emoteId: String, shownName: String, fresh: Boolean): EmoteAccess?

    /** [channel]'s emote sets; null if 7TV could not be asked. */
    suspend fun sets(channel: String, emoteId: String, name: String): List<EmoteSetChoice>?

    /** Adds the emote to [set], or to the channel's active set for null. */
    fun add(channel: String, set: EmoteSetChoice?, emoteId: String, name: String)

    /** In [setId], the active set the card was shown for; [EmoteAccess.activeSetId]. The same for [remove]. */
    fun rename(channel: String, setId: String, emoteId: String, name: String, newName: String)

    fun remove(channel: String, setId: String, emoteId: String, name: String)
}

/** What the sheet shows: the card itself, or one of the steps its 7TV buttons open in its place. */
private enum class CardStep { Card, Rename, Remove, AddToSet }

/**
 * Details of a tapped emote: the image on the left, its name, author and provider beside it, and
 * below them actions to insert it, copy its name, open its page or hide it. Every layer of a
 * stacked (zero-width) emote can be selected. Someone who may edit the channel's 7TV emotes also
 * gets buttons to rename, remove or add a 7TV emote; [editing] is null where that cannot be done.
 * Those open in the same sheet instead of a second one on top, and Cancel or Back returns to the card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmoteCardSheet(
    emotes: List<Emote>,
    channel: String,
    imageLoader: ImageLoader,
    onInsert: (Emote) -> Unit,
    /** Hides the emote in the given channel, or everywhere for null. */
    onHide: (Emote, String?) -> Unit,
    onDismiss: () -> Unit,
    editing: EmoteEditing? = null,
) {
    var selected by remember(emotes) { mutableIntStateOf(0) }
    val emote = emotes[selected.coerceIn(emotes.indices)]
    var step by remember { mutableStateOf(CardStep.Card) }

    // Starts empty for each emote, so a layer just picked never offers what the one before allowed.
    var access by remember(channel, emote.id) { mutableStateOf<EmoteAccess?>(null) }
    if (editing != null && emote.provider == EmoteProvider.SevenTv) {
        // Null until the first revision, which comes at once; asking before it would start twice.
        val revision by editing.revision.collectAsState(null)
        // Live updates come often; only the first ask after opening goes to 7TV, the rest use its cache.
        var asked by remember(channel, emote.id) { mutableStateOf(false) }
        LaunchedEffect(channel, emote.id, revision) {
            if (revision == null) return@LaunchedEffect
            access = editing.access(channel, emote.id, emote.name, fresh = !asked)
            asked = true
        }
    }
    val granted = access?.takeIf { editing != null }
    // A step needs what the card was shown with; without it, as after a removal elsewhere, the card is back.
    val shown = when {
        step == CardStep.Card || granted == null -> CardStep.Card
        step != CardStep.AddToSet && granted.name == null -> CardStep.Card
        else -> step
    }
    // Forgotten once it fell back, so it does not reopen by itself when access or the name returns.
    LaunchedEffect(shown) { if (shown == CardStep.Card) step = CardStep.Card }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Full height right away: a half-open sheet would put a step's buttons under the keyboard.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        // Back leaves a step for the card rather than closing everything.
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = shown == CardStep.Card),
    ) {
        // Read inside the sheet's window; outside they would be the screen's.
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        val back = {
            focus.clearFocus()
            keyboard?.hide()
            step = CardStep.Card
        }
        // Runs a step's action and closes the sheet, the keyboard first so it does not outlive it.
        val finish = { action: () -> Unit ->
            focus.clearFocus()
            keyboard?.hide()
            action()
            onDismiss()
        }
        NavigationBackHandler(
            state = rememberNavigationEventState(currentInfo = NavigationEventInfo.None),
            isBackEnabled = shown != CardStep.Card,
            onBackCompleted = back,
        )
        AnimatedContent(targetState = shown, label = "emote card step") { current ->
            if (current == CardStep.Card || editing == null || granted == null) {
                CardContent(emotes, emote, selected, { selected = it }, channel, imageLoader, onInsert, onHide, onDismiss) {
                    if (editing != null && granted != null) {
                        SevenTvEditorButtons(emote, channel, granted, editing, open = { step = it }, onDismiss = onDismiss)
                    }
                }
                return@AnimatedContent
            }
            // The name it has in the active set now; the message may be from before a rename.
            val name = granted.name ?: emote.name
            when (current) {
                CardStep.Card -> Unit
                CardStep.Rename -> RenameStep(
                    name, channel,
                    onRename = { newName -> finish { editing.rename(channel, granted.activeSetId, emote.id, name, newName) } },
                    onCancel = back,
                )
                CardStep.Remove -> CardStepLayout(
                    title = stringResource(R.string.emote_remove_title, name),
                    text = stringResource(R.string.emote_remove_text, channel),
                    confirmLabel = stringResource(R.string.emote_remove),
                    destructive = true,
                    onConfirm = { finish { editing.remove(channel, granted.activeSetId, emote.id, name) } },
                    onCancel = back,
                )
                CardStep.AddToSet -> AddToSetStep(
                    name, emote.id, channel, editing,
                    onAdd = { set -> finish { editing.add(channel, set, emote.id, name) } },
                    onCancel = back,
                )
            }
        }
    }
}

/** The card itself: header, stacked layers, the actions for everyone, then [editorButtons]. */
@Composable
private fun CardContent(
    emotes: List<Emote>,
    emote: Emote,
    selected: Int,
    onSelect: (Int) -> Unit,
    channel: String,
    imageLoader: ImageLoader,
    onInsert: (Emote) -> Unit,
    onHide: (Emote, String?) -> Unit,
    onDismiss: () -> Unit,
    editorButtons: @Composable () -> Unit,
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    // Scrolls in landscape, where the editor buttons at the end would otherwise be cut off.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp),
    ) {
        EmoteHeader(emote, imageLoader)

        if (emotes.size > 1) {
            Text(
                stringResource(R.string.emote_stack),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                emotes.forEachIndexed { i, e ->
                    AsyncImage(
                        model = e.url,
                        contentDescription = e.name,
                        imageLoader = imageLoader,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .then(
                                if (i == selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                                else Modifier
                            )
                            .clickable { onSelect(i) }
                            .padding(6.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        val actions = listOfNotNull(
            ButtonAction(R.string.emote_insert) { onInsert(emote); onDismiss() },
            ButtonAction(R.string.emote_copy_name) { clipboard.setText(AnnotatedString(emote.name)) },
            emote.pageUrl?.let { url ->
                ButtonAction(R.string.emote_open_page) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    } catch (e: ActivityNotFoundException) {
                        // No browser installed.
                    }
                }
            },
        )
        ConnectedButtons(actions, tonal = true)
        val hide = ButtonAction(
            R.string.emote_hide,
            choices = listOf(
                ButtonChoice(R.string.emote_hide_everywhere) { onHide(emote, null); onDismiss() },
                ButtonChoice(R.string.emote_hide_in_channel, channel) { onHide(emote, channel); onDismiss() },
            ),
        )
        ConnectedButtons(listOf(hide), tonal = false, compact = true, modifier = Modifier.padding(top = 8.dp))

        editorButtons()
    }
}

/** The emote large on the left; its name, author, provider and flags beside it. */
@Composable
private fun EmoteHeader(emote: Emote, imageLoader: ImageLoader) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // Wide emotes keep their shape up to a point; the text beside them needs room too.
        val imageWidth = (IMAGE_HEIGHT.value * EmoteSizes.aspectRatio(emote)).coerceIn(IMAGE_HEIGHT.value, IMAGE_MAX_WIDTH.value).dp
        // Large image, falling back to the normal size if there is none.
        var model by remember(emote) { mutableStateOf(emote.largeUrl) }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(12.dp),
        ) {
            AsyncImage(
                model = model,
                contentDescription = emote.name,
                imageLoader = imageLoader,
                contentScale = ContentScale.Fit,
                onError = { if (model != emote.url) model = emote.url },
                modifier = Modifier.size(width = imageWidth, height = IMAGE_HEIGHT),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Text(
                emote.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            emote.author?.let {
                Text(
                    stringResource(R.string.emote_by, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ProviderLine(emote)
            val flags = listOfNotNull(
                stringResource(R.string.emote_zero_width).takeIf { emote.zeroWidth },
                stringResource(R.string.emote_unlisted).takeIf { emote.unlisted },
            )
            if (flags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    flags.forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The provider's logo and name, and whether the emote is the channel's or global. */
@Composable
private fun ProviderLine(emote: Emote) {
    val provider = Provider.entries.firstOrNull { it.emotes == emote.provider }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
        provider?.let {
            Icon(
                painterResource(it.logo),
                contentDescription = null,
                tint = it.tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        val scope = stringResource(if (emote.isChannel) R.string.emote_channel else R.string.emote_global)
        Text(
            "${emote.provider.label} · $scope",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Rename, add to another set and remove for an emote the channel has; add to the channel or to
 * another set for one it does not. Only there for the streamer and the channel's 7TV editors.
 */
@Composable
private fun SevenTvEditorButtons(
    emote: Emote,
    channel: String,
    access: EmoteAccess,
    editing: EmoteEditing,
    open: (CardStep) -> Unit,
    onDismiss: () -> Unit,
) {
    Text(
        stringResource(R.string.emote_seventv_editor),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
    val addToSet = ButtonAction(R.string.emote_add_to_set) { open(CardStep.AddToSet) }
    val actions = if (access.name != null) {
        listOf(
            ButtonAction(R.string.emote_rename) { open(CardStep.Rename) },
            addToSet,
            ButtonAction(R.string.emote_remove, danger = true) { open(CardStep.Remove) },
        )
    } else {
        listOf(ButtonAction(R.string.emote_add_to_channel) { editing.add(channel, null, emote.id, emote.name); onDismiss() }, addToSet)
    }
    ConnectedButtons(actions, tonal = false)
}

/**
 * A step in place of the card, laid out like [me.lesh.chatter.ui.FormSheet]: a title, a line about
 * it, [content], and Cancel and [confirmLabel] at the bottom. The rest scrolls, so the buttons stay
 * in view in landscape or with large text.
 */
@Composable
private fun CardStepLayout(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp)) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            content()
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                // The default outline nearly vanishes on the sheet's surface.
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.cancel)) }
            Button(
                onClick = onConfirm,
                enabled = confirmEnabled,
                // Disabled, the default grey on grey hardly reads as a button with a label.
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    contentColor = if (destructive) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text(confirmLabel) }
        }
    }
}

/** Asks for the emote's new name in [channel], with its [current] one filled in. */
@Composable
private fun RenameStep(current: String, channel: String, onRename: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    val newName = name.trim()
    val valid = newName.isNotEmpty() && newName != current && newName.none { it.isWhitespace() }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    CardStepLayout(
        title = stringResource(R.string.emote_rename_title),
        text = stringResource(R.string.emote_rename_text, channel),
        confirmLabel = stringResource(R.string.emote_rename),
        confirmEnabled = valid,
        onConfirm = { onRename(newName) },
        onCancel = onCancel,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text(stringResource(R.string.emote_rename_label)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (valid) onRename(newName) }),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).focusRequester(focus),
        )
    }
}

/**
 * The channel's emote sets to add the emote [emoteId], called [name], to; those that have it
 * already are shown, but cannot be picked.
 */
@Composable
private fun AddToSetStep(name: String, emoteId: String, channel: String, editing: EmoteEditing, onAdd: (EmoteSetChoice) -> Unit, onCancel: () -> Unit) {
    val sets by produceState<SetsLoad>(SetsLoad.Loading, channel, emoteId) {
        value = editing.sets(channel, emoteId, name)?.let { SetsLoad.Loaded(it) } ?: SetsLoad.Failed
    }
    var picked by remember { mutableStateOf<EmoteSetChoice?>(null) }
    CardStepLayout(
        title = stringResource(R.string.emote_add_to_set_title),
        text = stringResource(R.string.emote_add_to_set_text, name),
        confirmLabel = stringResource(R.string.add),
        confirmEnabled = picked != null,
        onConfirm = { picked?.let(onAdd) },
        onCancel = onCancel,
    ) {
        when (val load = sets) {
            SetsLoad.Loading -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp))
            }
            SetsLoad.Failed -> SheetNote(stringResource(R.string.emote_sets_failed))
            is SetsLoad.Loaded -> if (load.sets.isEmpty()) {
                SheetNote(stringResource(R.string.emote_sets_none))
            } else {
                Column(Modifier.padding(top = 8.dp)) {
                    load.sets.forEach { set -> SetRow(set, selected = picked?.id == set.id, onPick = { picked = set }) }
                }
            }
        }
    }
}

private sealed interface SetsLoad {
    data object Loading : SetsLoad
    data object Failed : SetsLoad
    data class Loaded(val sets: List<EmoteSetChoice>) : SetsLoad
}

@Composable
private fun SetRow(set: EmoteSetChoice, selected: Boolean, onPick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, enabled = !set.hasEmote, role = Role.RadioButton, onClick = onPick)
            .padding(vertical = 4.dp),
    ) {
        if (set.hasEmote) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.size(48.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                set.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (set.hasEmote) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val notes = listOfNotNull(
                stringResource(R.string.emote_set_active).takeIf { set.active },
                stringResource(R.string.emote_set_has_it).takeIf { set.hasEmote },
            )
            if (notes.isNotEmpty()) {
                Text(
                    notes.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SheetNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 16.dp),
    )
}

/** How tall the card draws the emote. */
private val IMAGE_HEIGHT = 72.dp
private val IMAGE_MAX_WIDTH = 144.dp
