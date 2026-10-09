package dev.chatter.app.ui.settings

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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chatter.app.R
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label(value))
                preview?.let {
                    Spacer(Modifier.width(12.dp))
                    it(value)
                }
            }
        },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
        colors = transparentItem(),
        modifier = Modifier.clickable { open = true },
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
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = transparentItem(),
        modifier = Modifier.clickable { onChange(!checked) },
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
        trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
        colors = transparentItem(),
        modifier = Modifier.clickable(onClick = onClick),
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
    }
    Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(addTitle))
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

@Composable
internal fun SliderItem(
    title: String,
    value: Float,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Slider(value = value, onValueChange = onChange, onValueChangeFinished = onDone, valueRange = range, steps = steps)
        },
        colors = transparentItem(),
    )
}
