package me.lesh.chatter.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
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
import me.lesh.chatter.channels.StreamDetails
import me.lesh.chatter.channels.StreamLanguages
import me.lesh.chatter.channels.StreamTags
import me.lesh.chatter.channels.settingsChange
import me.lesh.chatter.emotes.ChannelEmoteSet
import me.lesh.chatter.ui.FormSheet
import java.util.Locale

/** Twitch's limit for a stream title, in characters. */
private const val TITLE_MAX = 140

/**
 * What the user typed and picked on the channel page's Settings tab, starting from [form]. Kept
 * above the tabs, so switching to the channel and back loses nothing.
 */
@Stable
class ChannelSettingsState(val form: ChannelSettingsForm) {
    val stream = (form.stream as? SettingsPart.Ready)?.value
    val sets = (form.emoteSets as? SettingsPart.Ready)?.value?.sets

    var title by mutableStateOf(stream?.title.orEmpty())
    var category by mutableStateOf(stream?.category)

    /** The category search is open in place of the category. */
    var searching by mutableStateOf(false)
    var query by mutableStateOf("")
    var tags by mutableStateOf(stream?.tags.orEmpty())
    var tagInput by mutableStateOf("")
    var language by mutableStateOf(stream?.language.orEmpty())
    var choosingLanguage by mutableStateOf(false)
    var chosenSet by mutableStateOf(sets?.firstOrNull { it.active })

    // Most visits are for the title; the sets stay folded until asked for.
    var setsOpen by mutableStateOf(false)

    val change: ChannelSettingsChange
        get() = settingsChange(stream, title, category, category?.name.orEmpty(), chosenSet, tags, language)

    /** Twitch takes no empty title. */
    val canSave: Boolean get() = change != ChannelSettingsChange() && (stream == null || title.isNotBlank())

    /**
     * This state after [saved] went through: what was saved becomes the starting point, and whatever
     * was typed or opened meanwhile stays.
     */
    fun saved(saved: ChannelSettingsChange): ChannelSettingsState {
        val now = this
        val stream = (form.stream as? SettingsPart.Ready)?.value?.let { old ->
            SettingsPart.Ready(
                StreamDetails(
                    title = saved.title ?: old.title,
                    category = when (saved.category) {
                        null -> old.category
                        StreamCategory.NONE -> null
                        else -> saved.category
                    },
                    tags = saved.tags ?: old.tags,
                    language = saved.language ?: old.language,
                ),
            )
        } ?: form.stream
        val sets = (form.emoteSets as? SettingsPart.Ready)?.value?.let { old ->
            val switched = saved.emoteSet ?: return@let null
            SettingsPart.Ready(old.copy(sets = old.sets.map { it.copy(active = it.id == switched.id) }))
        } ?: form.emoteSets
        return ChannelSettingsState(ChannelSettingsForm(stream, sets)).apply {
            title = now.title
            category = now.category
            searching = now.searching
            query = now.query
            tags = now.tags
            tagInput = now.tagInput
            language = now.language
            choosingLanguage = now.choosingLanguage
            chosenSet = (sets as? SettingsPart.Ready)?.value?.sets?.firstOrNull { it.id == now.chosenSet?.id }
            setsOpen = now.setsOpen
        }
    }

    fun addTag(): Boolean {
        val added = StreamTags.add(tags, tagInput) ?: return false
        tags = added
        tagInput = ""
        return true
    }
}

/**
 * The Settings tab: what the user may change about the channel. The title, category, tags and
 * language of their own channel, and its active 7TV emote set where they are the streamer or a 7TV
 * editor. Parts they may not change do not show; Save sends only what changed.
 */
fun LazyListScope.channelSettingsItems(
    state: ChannelSettingsState?,
    sevenTvLoggedIn: Boolean,
    imageLoader: ImageLoader,
    searchCategories: suspend (String) -> List<StreamCategory>,
) {
    if (state == null) {
        item(key = "settings-loading") {
            Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp))
            }
        }
        return
    }
    val form = state.form
    if (form.stream == SettingsPart.Hidden && form.emoteSets == SettingsPart.Hidden) {
        item(key = "settings-nothing") { PageNote(stringResource(R.string.channel_settings_nothing)) }
    }

    when (form.stream) {
        SettingsPart.Hidden -> {}
        SettingsPart.NeedsLogin -> {
            heading("stream", R.string.channel_settings_stream)
            item(key = "stream-login") { PageNote(stringResource(R.string.channel_settings_stream_login)) }
        }
        SettingsPart.Failed -> {
            heading("stream", R.string.channel_settings_stream)
            item(key = "stream-failed") { PageNote(stringResource(R.string.channel_settings_stream_failed)) }
        }
        is SettingsPart.Ready -> {
            heading("stream", R.string.channel_settings_stream)
            item(key = "stream-category") { CategoryPart(state, imageLoader, searchCategories) }
            item(key = "stream-title") {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = { state.title = it.replace('\n', ' ').take(TITLE_MAX) },
                    label = { Text(stringResource(R.string.channel_settings_title)) },
                    supportingText = { Text("${state.title.length}/$TITLE_MAX") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp),
                )
            }
            item(key = "tags-heading") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
                    Text(
                        stringResource(R.string.channel_tags),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.channel_tags_count, state.tags.size, StreamTags.MAX_TAGS),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "tags") { TagsPart(state) }
            heading("more", R.string.channel_more)
            item(key = "language") {
                SettingRow(
                    icon = { Icon(painterResource(R.drawable.ic_language), null) },
                    title = stringResource(R.string.channel_language),
                    summary = languageName(state.language),
                    onClick = { state.choosingLanguage = true },
                )
            }
        }
    }

    when (val part = form.emoteSets) {
        SettingsPart.Hidden, SettingsPart.NeedsLogin -> {}
        SettingsPart.Failed -> {
            item(key = "sets-header") {
                SetsHeader(summary = stringResource(R.string.emote_sets_failed), open = null, sevenTvUserId = null, onToggle = {})
            }
        }
        is SettingsPart.Ready -> {
            item(key = "sets-header") {
                SetsHeader(
                    summary = state.chosenSet?.name ?: stringResource(R.string.emote_sets_none),
                    open = state.setsOpen,
                    sevenTvUserId = part.value.sevenTvUserId,
                    onToggle = { state.setsOpen = !state.setsOpen },
                )
            }
            if (state.setsOpen) {
                items(part.value.sets, key = { "set:${it.id}" }) { set ->
                    SetRow(set, selected = state.chosenSet?.id == set.id) { state.chosenSet = set }
                }
                if (!sevenTvLoggedIn && part.value.sets.size > 1) {
                    item(key = "sets-login") { PageNote(stringResource(R.string.channel_settings_seventv_login)) }
                }
            }
        }
    }
}

/**
 * The stream language picker, starting from [current]. [onPick] and [onDismiss] go to the page,
 * whose settings may have been replaced by a save while the sheet was open.
 */
@Composable
fun LanguageSheet(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var picked by remember { mutableStateOf(current) }
    // A language Twitch knows and the list does not stays offered, so opening the sheet changes nothing.
    val codes = remember { StreamLanguages.CODES + listOf(current).filter { it.isNotEmpty() && it !in StreamLanguages.CODES } }
    FormSheet(
        title = stringResource(R.string.channel_language),
        confirmLabel = stringResource(R.string.save),
        onConfirm = { onPick(picked) },
        onDismiss = onDismiss,
    ) {
        items(codes, key = { "language:$it" }) { code ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .selectable(selected = code == picked, role = Role.RadioButton, onClick = { picked = code }),
            ) {
                RadioButton(selected = code == picked, onClick = null, modifier = Modifier.size(48.dp))
                Text(languageName(code), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** [code] as a language name in the app's language, like "German" for "de". */
@Composable
internal fun languageName(code: String): String {
    if (code == StreamLanguages.OTHER) return stringResource(R.string.channel_language_other)
    if (code.isEmpty()) return ""
    val appLocale = LocalConfiguration.current.locales[0]
    return Locale.forLanguageTag(code).getDisplayName(appLocale).replaceFirstChar { it.titlecase(appLocale) }.ifEmpty { code }
}

/**
 * The category with its cover and a button to change it, or the search that changes it. A blank
 * search with the clear button takes the category away.
 */
@Composable
private fun CategoryPart(state: ChannelSettingsState, imageLoader: ImageLoader, searchCategories: suspend (String) -> List<StreamCategory>) {
    if (!state.searching) {
        val category = state.category
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(12.dp),
        ) {
            CategoryCover(category?.boxArtUrl, imageLoader, width = 52, height = 72)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.channel_settings_category),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    category?.name ?: stringResource(R.string.channel_settings_no_category),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledTonalButton(onClick = { state.query = ""; state.searching = true }) {
                Text(stringResource(R.string.channel_category_change))
            }
        }
        return
    }
    var results by remember { mutableStateOf<List<StreamCategory>>(emptyList()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(state.query) {
        if (state.query.isBlank()) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(300) // debounce typing
        results = searchCategories(state.query)
    }
    Column(Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = { state.query = it },
            singleLine = true,
            label = { Text(stringResource(R.string.channel_category_search)) },
            supportingText = { Text(stringResource(R.string.channel_settings_category_pick)) },
            trailingIcon = {
                IconButton(onClick = {
                    // Cleared while blank, the field asks for no category at all.
                    if (state.query.isEmpty()) state.category = null
                    state.query = ""
                    state.searching = false
                }) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = stringResource(
                            if (state.query.isEmpty()) R.string.channel_settings_category_clear else R.string.cancel,
                        ),
                    )
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        results.forEach { result ->
            CategoryRow(result, imageLoader) {
                state.category = result
                state.searching = false
            }
        }
    }
}

/** The channel's tags as chips to remove, and a field to add one. */
@Composable
private fun TagsPart(state: ChannelSettingsState) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        if (state.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.tags.forEach { tag ->
                    InputChip(
                        selected = false,
                        onClick = { state.tags -= tag },
                        label = { Text(tag) },
                        trailingIcon = {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.channel_tag_remove, tag), modifier = Modifier.size(18.dp))
                        },
                    )
                }
            }
        }
        if (state.tags.size < StreamTags.MAX_TAGS) {
            val valid = state.tagInput.isBlank() || StreamTags.add(state.tags, state.tagInput) != null
            OutlinedTextField(
                value = state.tagInput,
                onValueChange = { state.tagInput = it.take(StreamTags.MAX_LENGTH + 1) },
                singleLine = true,
                label = { Text(stringResource(R.string.channel_tag_add)) },
                placeholder = { Text(stringResource(R.string.channel_tag_placeholder)) },
                isError = !valid,
                supportingText = { Text(stringResource(R.string.channel_tag_rules)) },
                trailingIcon = if (state.tagInput.isNotBlank() && valid) ({
                    IconButton(onClick = { state.addTag() }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.channel_tag_add))
                    }
                }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { state.addTag() }),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

/** A category's cover in its own shape, a quiet box while there is none. Sizes are in dp. */
@Composable
internal fun CategoryCover(url: String?, imageLoader: ImageLoader, width: Int, height: Int) {
    AsyncImage(
        model = url?.takeIf { it.isNotEmpty() },
        contentDescription = null,
        imageLoader = imageLoader,
        modifier = Modifier
            .size(width = width.dp, height = height.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

@Composable
private fun SettingRow(icon: @Composable () -> Unit, title: String, summary: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        icon()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary.isNotEmpty()) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun LazyListScope.heading(key: String, text: Int) {
    item(key = "heading:$key") {
        Text(
            stringResource(text),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
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
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
        // The fold alone is one target, so the link next to it stays a button of its own.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
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
                .padding(start = 4.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
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
        CategoryCover(category.boxArtUrl, imageLoader, width = 30, height = 40)
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
            .padding(horizontal = 12.dp)
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
internal fun PageNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
