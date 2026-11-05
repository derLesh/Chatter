package me.lesh.chatter.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.lesh.chatter.R
import kotlinx.coroutines.launch

/**
 * A choice on one row: label, current value, and the options in a sheet. Radio buttons cost a row
 * per option; the name colors alone were six rows with previews. Choices of two or three short
 * options stay spelled out.
 *
 * [preview] draws what a label cannot say, next to each option and next to the current value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> ChoiceItem(
    title: Int,
    value: T,
    options: List<T>,
    label: @Composable (T) -> String,
    onPick: (T) -> Unit,
    hint: Int? = null,
    preview: (@Composable RowScope.(T) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    ListItem(
        headlineContent = { Text(stringResource(title)) },
        // The row shows the preview too; for a palette the look is the thing being chosen.
        supportingContent = {
            // On one baseline: centred, a preview in a taller fallback font (Korean) put the two
            // baselines apart, and ListItem then took the row for three lines.
            Row {
                Text(label(value), Modifier.alignByBaseline())
                preview?.let {
                    Spacer(Modifier.width(12.dp))
                    Row(Modifier.alignByBaseline()) { it(value) }
                }
            }
        },
        trailingContent = { NavChevron() },
        colors = transparentItem(),
        modifier = Modifier.tileClickable { open = true },
    )
    if (!open) return

    // Picking closes the sheet.
    val pick = { option: T ->
        onPick(option)
        scope.launch { sheet.hide() }.invokeOnCompletion { if (!sheet.isVisible) open = false }
        Unit
    }
    ModalBottomSheet(
        onDismissRequest = { open = false },
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            hint?.let {
                Text(
                    stringResource(it),
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
                        .clickable { pick(option) }
                        .padding(vertical = 4.dp),
                ) {
                    RadioButton(selected = option == value, onClick = { pick(option) })
                    Text(label(option), style = MaterialTheme.typography.bodyLarge)
                    preview?.let {
                        Spacer(Modifier.width(12.dp))
                        it(option)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SwitchItem(res: Int, checked: Boolean, onChange: (Boolean) -> Unit, hint: Int? = null) {
    ListItem(
        headlineContent = { Text(stringResource(res)) },
        supportingContent = hint?.let { { Text(stringResource(it)) } },
        trailingContent = { SettingsSwitch(checked = checked, onCheckedChange = onChange) },
        colors = transparentItem(),
        modifier = Modifier.tileClickable { onChange(!checked) },
    )
}

/** Entry to a word list: what it is for and how many words it has. */
@Composable
internal fun KeywordListItem(title: Int, summary: Int, words: List<String>, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = {
            Text(
                if (words.isEmpty()) stringResource(summary)
                else pluralStringResource(R.plurals.keywords_count, words.size, words.size)
            )
        },
        trailingContent = { NavChevron() },
        colors = transparentItem(),
        modifier = Modifier.tileClickable(onClick = onClick),
    )
}

/** The last row of a list the user builds up, like rules, words or blocked users. */
@Composable
internal fun AddItem(title: Int, hint: Int? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = hint?.let { { Text(stringResource(it)) } },
        leadingContent = { CategoryIcon(Icons.Default.Add) },
        colors = transparentItem(),
        modifier = Modifier.tileClickable(onClick = onClick),
    )
}

/** A list of words built one at a time, each a row with its own remove button. */
@Composable
internal fun KeywordsPage(
    words: List<String>,
    emptyText: Int,
    hint: Int,
    addTitle: Int,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }

    SettingsGroup {
        if (words.isEmpty()) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(emptyText)) },
                    supportingContent = { Text(stringResource(hint)) },
                    colors = transparentItem(),
                )
            }
        }
        words.forEach { word ->
            item {
                ListItem(
                    headlineContent = { Text(word) },
                    trailingContent = {
                        IconButton(onClick = { onRemove(word) }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.keyword_remove, word))
                        }
                    },
                    colors = transparentItem(),
                )
            }
        }
        item { AddItem(addTitle) { adding = true } }
    }

    if (adding) {
        AddKeywordDialog(
            title = addTitle,
            hint = hint,
            onAdd = onAdd,
            onDismiss = { adding = false },
        )
    }
}

/**
 * A number picked on a slider, its value at the end of the title line. [below] goes under the
 * slider, for a preview that follows the finger.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SliderItem(
    title: Int,
    valueLabel: String,
    value: Float,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    below: @Composable () -> Unit = {},
) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(title), Modifier.weight(1f))
                // The number rolls up or down with the slider, like a counter.
                AnimatedContent(
                    targetState = value to valueLabel,
                    transitionSpec = {
                        // Short, so a drag through several steps still shows a readable number.
                        val up = targetState.first > initialState.first
                        val roll = tween<IntOffset>(ROLL_MS)
                        (slideInVertically(roll) { if (up) it else -it } + fadeIn(tween(ROLL_MS))) togetherWith
                            (slideOutVertically(roll) { if (up) -it else it } + fadeOut(tween(ROLL_MS))) using SizeTransform(clip = true)
                    },
                    contentAlignment = Alignment.CenterEnd,
                    modifier = Modifier.clipToBounds(),
                    label = "slider-value",
                ) { (_, label) ->
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        },
        supportingContent = {
            Column {
                val state = remember(range, steps) { SliderState(value, steps, range) }
                LaunchedEffect(value) { if (state.value != value) state.value = value }
                // The end dot would sit on the last tick; the ticks alone show the steps.
                Slider(
                    state = state,
                    onValueChange = {
                        state.value = it
                        onChange(state.value)
                    },
                    onValueChangeFinished = onDone,
                    track = { SliderDefaults.Track(sliderState = it, drawStopIndicator = null) },
                )
                below()
            }
        },
        colors = transparentItem(),
    )
}

private const val ROLL_MS = 110
