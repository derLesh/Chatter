package me.lesh.chatter.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import me.lesh.chatter.R
import me.lesh.chatter.settings.Settings
import me.lesh.chatter.ui.theme.highlightBackground
import me.lesh.chatter.ui.theme.highlightColor
import kotlin.math.roundToInt

private val HIGHLIGHT_COLORS = listOf(
    Settings.HIGHLIGHT_DEFAULT, Settings.HIGHLIGHT_ACCENT,
    0xFFFF9800.toInt(), 0xFFFFC107.toInt(), 0xFF4CAF50.toInt(), 0xFF00BCD4.toInt(),
    0xFF2196F3.toInt(), 0xFF9C27B0.toInt(), 0xFFE91E63.toInt(),
)

private const val SWATCHES_PER_ROW = 5
private val SWATCH_TOUCH_SIZE = 48.dp

/** Presets and a custom color, shared by appearance settings and onboarding. */
@Composable
internal fun HighlightColorPicker(selected: Int, onSelect: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var customOpen by rememberSaveable { mutableStateOf(false) }
    val customSelected = selected !in HIGHLIGHT_COLORS
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_highlight_color)) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_highlight_color_hint))
                val customLabel = stringResource(R.string.highlight_custom)
                // Five to a row from edge to edge, so none hides behind the edge; the last place is
                // the custom color. ListItem measures intrinsically, which lazy grids do not support.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    (0..HIGHLIGHT_COLORS.size).chunked(SWATCHES_PER_ROW).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            row.forEach { index ->
                                val option = HIGHLIGHT_COLORS.getOrNull(index)
                                if (option != null) {
                                    val color = highlightColor(option, scheme)
                                    val label = when (option) {
                                        Settings.HIGHLIGHT_DEFAULT -> stringResource(R.string.highlight_default)
                                        Settings.HIGHLIGHT_ACCENT -> stringResource(R.string.highlight_accent)
                                        else -> stringResource(R.string.highlight_color_value, colorHex(color))
                                    }
                                    Swatch(color, selected == option, label, { onSelect(option) }) {
                                        if (option == selected) Icon(Icons.Default.Check, null, tint = contentColorOn(color))
                                    }
                                } else {
                                    val customColor = if (customSelected) Color(selected) else scheme.surfaceContainerHigh
                                    Swatch(customColor, customSelected, customLabel, { customOpen = true }) {
                                        Icon(Icons.Default.Edit, null, tint = contentColorOn(customColor))
                                    }
                                }
                            }
                            repeat(SWATCHES_PER_ROW - row.size) { Spacer(Modifier.size(SWATCH_TOUCH_SIZE)) }
                        }
                    }
                }
                HighlightPreview(selected)
            }
        },
        colors = transparentItem(),
    )
    if (customOpen) {
        CustomHighlightDialog(
            initialColor = highlightColor(selected, scheme),
            onDismiss = { customOpen = false },
            onSave = { onSelect(it); customOpen = false },
        )
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, label: String, onClick: () -> Unit, mark: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(SWATCH_TOUCH_SIZE)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp).clip(CircleShape).background(color)
                .border(if (selected) 3.dp else 1.dp, if (selected) scheme.onSurface else scheme.outline, CircleShape),
        ) { mark() }
    }
}

private fun contentColorOn(color: Color) = if (color.luminance() > 0.179f) Color.Black else Color.White

/** Keep the draft separate so dismissing the dialog leaves the saved color untouched. */
@Composable
private fun CustomHighlightDialog(initialColor: Color, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    val initialHsv = remember(initialColor) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initialColor.toArgb(), it) }
    }
    var hue by rememberSaveable { mutableFloatStateOf(initialHsv[0]) }
    var saturation by rememberSaveable { mutableFloatStateOf(initialHsv[1]) }
    var brightness by rememberSaveable { mutableFloatStateOf(initialHsv[2]) }
    val color = Color.hsv(hue, saturation, brightness)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.highlight_custom)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(color)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                    Text(colorHex(color), style = MaterialTheme.typography.titleMedium)
                }
                HighlightPreview(color.toArgb())
                ColorSlider(
                    R.string.highlight_hue, hue, 0f..360f, { hue = it },
                    (0..6).map { Color.hsv(it * 60f, 1f, 1f) },
                )
                ColorSlider(
                    R.string.highlight_saturation, saturation, 0f..1f, { saturation = it },
                    listOf(Color.hsv(hue, 0f, brightness), Color.hsv(hue, 1f, brightness)),
                )
                ColorSlider(
                    R.string.highlight_brightness, brightness, 0f..1f, { brightness = it },
                    listOf(Color.Black, Color.hsv(hue, saturation, 1f)),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(color.toArgb()) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Native sliders keep touch, keyboard and TalkBack controls over the color gradients. */
@Composable
private fun ColorSlider(label: Int, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, colors: List<Color>) {
    val name = stringResource(label)
    val amount = if (range.endInclusive == 360f) "${value.roundToInt()}°" else "${(value * 100).roundToInt()}%"
    Column {
        Text("$name · $amount", style = MaterialTheme.typography.labelLarge)
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape)
                .background(Brush.horizontalGradient(colors))
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
            Slider(
                value = value, onValueChange = onChange, valueRange = range,
                colors = SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent),
                modifier = Modifier.semantics { contentDescription = name },
            )
        }
    }
}

/** Composite over the chat surface, even when the picker sits on a lighter settings card. */
@Composable
private fun HighlightPreview(selected: Int) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.surface, shape = RoundedCornerShape(12.dp)) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(stringResource(R.string.settings_highlight_preview_name))
                    append(": ")
                }
                append(stringResource(R.string.settings_highlight_preview_text))
            },
            color = scheme.onSurface,
            modifier = Modifier.fillMaxWidth().background(highlightBackground(selected, scheme)).padding(12.dp),
        )
    }
}

private fun colorHex(color: Color): String = "#%06X".format(color.toArgb() and 0xFFFFFF)
