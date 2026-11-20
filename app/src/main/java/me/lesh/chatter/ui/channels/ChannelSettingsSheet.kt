package me.lesh.chatter.ui.channels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import me.lesh.chatter.R
import me.lesh.chatter.channels.ChannelSettingsChange
import me.lesh.chatter.channels.ChannelSettingsForm
import me.lesh.chatter.channels.SettingsPart
import me.lesh.chatter.channels.StreamCategory
import me.lesh.chatter.channels.settingsChange
import me.lesh.chatter.emotes.ChannelEmoteSet
import me.lesh.chatter.ui.FormSheet

/** Twitch's limit for a stream title, in characters. */
private const val TITLE_MAX = 140

/**
 * Changes what the user may change about a channel: the title and category of their own channel,
 * and its active 7TV emote set where they are the streamer or a 7TV editor. Parts they may not
 * change do not show; Save sends only what changed.
 */
@Composable
fun ChannelSettingsSheet(
    channelName: String,
    load: suspend () -> ChannelSettingsForm,
    searchCategories: suspend (String) -> List<StreamCategory>,
    sevenTvLoggedIn: Boolean,
    imageLoader: ImageLoader,
    onSave: (ChannelSettingsChange) -> Unit,
    onDismiss: () -> Unit,
) {
    val form by produceState<ChannelSettingsForm?>(null) { value = load() }
    val stream = (form?.stream as? SettingsPart.Ready)?.value
    val sets = (form?.emoteSets as? SettingsPart.Ready)?.value?.sets

    var title by remember(stream) { mutableStateOf(stream?.title.orEmpty()) }
    // The category last picked, and what the field shows; they differ while the user searches.
    var category by remember(stream) { mutableStateOf(stream?.category) }
    var query by remember(stream) { mutableStateOf(stream?.category?.name.orEmpty()) }
    var results by remember { mutableStateOf<List<StreamCategory>>(emptyList()) }
    var chosenSet by remember(sets) { mutableStateOf(sets?.firstOrNull { it.active }) }
    // Most visits are for the title; the sets stay folded until asked for.
    var setsOpen by rememberSaveable { mutableStateOf(false) }

    val searching = query.isNotBlank() && query != category?.name
    LaunchedEffect(query, searching) {
        if (!searching) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(300) // debounce typing
        results = searchCategories(query)
    }

    val change = settingsChange(stream, title, category, query, chosenSet)

    FormSheet(
        title = stringResource(R.string.channel_settings),
        text = channelName,
        confirmLabel = stringResource(R.string.save),
        onConfirm = { onSave(change) },
        onDismiss = onDismiss,
        // Twitch takes no empty title, and a half-typed category is no category.
        confirmEnabled = change != ChannelSettingsChange() && !searching && (stream == null || title.isNotBlank()),
    ) {
        val loaded = form
        if (loaded == null) {
            item(key = "settings-loading") {
                Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            }
            return@FormSheet
        }
        if (loaded.stream == SettingsPart.Hidden && loaded.emoteSets == SettingsPart.Hidden) {
            item(key = "settings-nothing") { SheetNote(stringResource(R.string.channel_settings_nothing)) }
        }

        when (loaded.stream) {
            SettingsPart.Hidden -> {}
            SettingsPart.NeedsLogin -> {
                heading("stream", R.string.channel_settings_stream)
                item(key = "stream-login") { SheetNote(stringResource(R.string.channel_settings_stream_login)) }
            }
            SettingsPart.Failed -> {
                heading("stream", R.string.channel_settings_stream)
                item(key = "stream-failed") { SheetNote(stringResource(R.string.channel_settings_stream_failed)) }
            }
            is SettingsPart.Ready -> {
                heading("stream", R.string.channel_settings_stream)
                item(key = "stream-title") {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it.replace('\n', ' ').take(TITLE_MAX) },
                        label = { Text(stringResource(R.string.channel_settings_title)) },
                        supportingText = { Text("${title.length}/$TITLE_MAX") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item(key = "stream-category") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.channel_settings_category)) },
                        placeholder = { Text(stringResource(R.string.channel_settings_no_category)) },
                        supportingText = if (searching) ({ Text(stringResource(R.string.channel_settings_category_pick)) }) else null,
                        trailingIcon = if (query.isNotEmpty()) ({
                            IconButton(onClick = { query = ""; category = null }) {
                                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.channel_settings_category_clear))
                            }
                        }) else null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
                if (searching) {
                    items(results, key = { "category:${it.id}" }) { result ->
                        CategoryRow(result, imageLoader) {
                            category = result
                            query = result.name
                        }
                    }
                }
            }
        }

        when (val part = loaded.emoteSets) {
            SettingsPart.Hidden, SettingsPart.NeedsLogin -> {}
            SettingsPart.Failed -> {
                item(key = "sets-header") {
                    SetsHeader(summary = stringResource(R.string.emote_sets_failed), open = null, sevenTvUserId = null, onToggle = {})
                }
            }
            is SettingsPart.Ready -> {
                item(key = "sets-header") {
                    SetsHeader(
                        summary = chosenSet?.name ?: stringResource(R.string.emote_sets_none),
                        open = setsOpen,
                        sevenTvUserId = part.value.sevenTvUserId,
                        onToggle = { setsOpen = !setsOpen },
                    )
                }
                if (setsOpen) {
                    items(part.value.sets, key = { "set:${it.id}" }) { set ->
                        SetRow(set, selected = chosenSet?.id == set.id) { chosenSet = set }
                    }
                    if (!sevenTvLoggedIn && part.value.sets.size > 1) {
                        item(key = "sets-login") { SheetNote(stringResource(R.string.channel_settings_seventv_login)) }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.heading(key: String, text: Int) {
    item(key = "heading:$key") {
        Text(
            stringResource(text),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        )
    }
}

/**
 * The fold of the 7TV part: what it is, [summary] (the chosen set), a link to the channel's page on
 * 7TV where there are more settings, and the arrow. [open] is null where there is nothing to unfold.
 */
@Composable
private fun SetsHeader(summary: String, open: Boolean?, sevenTvUserId: String?, onToggle: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp).fillMaxWidth()) {
        // The fold alone is one target, so the link next to it stays a button of its own.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .then(if (open != null) Modifier.clickable(onClick = onToggle) else Modifier)
                // Says "expanded" or "collapsed" in the phone's language.
                .semantics {
                    when (open) {
                        true -> collapse { onToggle(); true }
                        false -> expand { onToggle(); true }
                        null -> {}
                    }
                }
                .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Icon(painterResource(R.drawable.ic_provider_seventv), contentDescription = null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.channel_settings_emote_set), style = MaterialTheme.typography.bodyLarge)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (open != null) {
                Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
            }
        }
        if (sevenTvUserId != null) {
            IconButton(onClick = {
                // Without a browser there is nowhere to open it.
                runCatching { uriHandler.openUri("https://7tv.app/users/$sevenTvUserId") }
            }) {
                Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = stringResource(R.string.channel_settings_open_seventv))
            }
        }
    }
}

@Composable
private fun CategoryRow(category: StreamCategory, imageLoader: ImageLoader, onPick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onPick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        AsyncImage(
            model = category.boxArtUrl,
            contentDescription = null,
            imageLoader = imageLoader,
            modifier = Modifier.size(width = 30.dp, height = 40.dp).clip(RoundedCornerShape(4.dp)),
        )
        Text(category.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SetRow(set: ChannelEmoteSet, selected: Boolean, onPick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onPick)
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.size(48.dp))
        Column(Modifier.weight(1f)) {
            Text(set.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (set.active) {
                Text(
                    stringResource(R.string.emote_set_active),
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
        modifier = Modifier.padding(vertical = 8.dp),
    )
}
