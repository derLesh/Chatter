package dev.chatter.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.chatter.app.R
import dev.chatter.app.chat.ChatRule
import dev.chatter.app.chat.RuleAction
import dev.chatter.app.chat.RuleTarget
import java.util.UUID

/** The colors a rule can paint a message with; the first one means "use the mention color". */
private val RULE_COLORS = listOf(
    null,
    0xFFFF9800.toInt(), 0xFFFFC107.toInt(), 0xFF4CAF50.toInt(), 0xFF00BCD4.toInt(),
    0xFF2196F3.toInt(), 0xFF9C27B0.toInt(), 0xFFE91E63.toInt(), 0xFFF44336.toInt(),
)

/**
 * Writes one rule: what to look for, where to look, and what to do about it. A new rule starts
 * from [ChatRule]'s defaults, an existing one is edited in place (same id).
 */
@Composable
fun RuleDialog(rule: ChatRule?, onSave: (ChatRule) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(rule ?: ChatRule(id = UUID.randomUUID().toString(), pattern = "")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (rule == null) R.string.rule_new else R.string.rule_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = draft.pattern,
                    onValueChange = { draft = draft.copy(pattern = it) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.rule_pattern)) },
                    supportingText = {
                        Text(stringResource(if (draft.regex) R.string.rule_pattern_regex_hint else R.string.rule_pattern_hint))
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Label(R.string.rule_target)
                ChipRow(
                    entries = listOf(
                        RuleTarget.Message to R.string.rule_target_message,
                        RuleTarget.Author to R.string.rule_target_author,
                        RuleTarget.Any to R.string.rule_target_any,
                    ),
                    selected = draft.target,
                    onSelect = { draft = draft.copy(target = it) },
                )

                Label(R.string.rule_action)
                ChipRow(
                    entries = listOf(
                        RuleAction.Highlight to R.string.rule_action_highlight,
                        RuleAction.Notify to R.string.rule_action_notify,
                        RuleAction.Hide to R.string.rule_action_hide,
                    ),
                    selected = draft.action,
                    onSelect = { draft = draft.copy(action = it) },
                )

                // A hidden message is never drawn, so its color would be a promise nobody keeps.
                if (draft.action != RuleAction.Hide) {
                    Label(R.string.rule_color)
                    ColorRow(draft.color) { draft = draft.copy(color = it) }
                }

                OutlinedTextField(
                    value = draft.channel.orEmpty(),
                    onValueChange = { draft = draft.copy(channel = it.trim().lowercase().ifBlank { null }) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.rule_channel)) },
                    supportingText = { Text(stringResource(R.string.rule_channel_hint)) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.rule_regex))
                        Text(
                            stringResource(R.string.rule_regex_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = draft.regex, onCheckedChange = { draft = draft.copy(regex = it) })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(draft.copy(pattern = draft.pattern.trim())); onDismiss() },
                enabled = draft.pattern.isNotBlank(),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Label(res: Int) {
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(res),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun <T> ChipRow(entries: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
    ) {
        entries.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(stringResource(label)) },
            )
        }
    }
}

@Composable
private fun ColorRow(selected: Int?, onSelect: (Int?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
    ) {
        RULE_COLORS.forEach { option ->
            val color = option?.let { Color(it) } ?: scheme.surfaceContainerHighest
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color)
                    .then(if (option == selected) Modifier.border(3.dp, scheme.onSurface, CircleShape) else Modifier)
                    .clickable { onSelect(option) },
            ) {
                if (option == selected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                    )
                }
            }
        }
    }
}
