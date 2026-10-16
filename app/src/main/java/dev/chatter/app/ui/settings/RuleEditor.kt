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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chatter.app.R
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.ChatRule
import dev.chatter.app.chat.PatternProblem
import dev.chatter.app.chat.RuleAction
import dev.chatter.app.chat.RuleEngine
import dev.chatter.app.chat.RuleOutcome
import dev.chatter.app.chat.RulePreview
import dev.chatter.app.chat.RuleResult
import dev.chatter.app.chat.RuleTarget
import dev.chatter.app.net.AppJson
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Colors a rule can use; the first means "use the mention color". */
private val RULE_COLORS = listOf(
    null,
    0xFFFF9800.toInt(), 0xFFFFC107.toInt(), 0xFF4CAF50.toInt(), 0xFF00BCD4.toInt(),
    0xFF2196F3.toInt(), 0xFF9C27B0.toInt(), 0xFFE91E63.toInt(), 0xFFF44336.toInt(),
)

/** Keeps a rule being edited through a turn of the phone. */
private val RuleSaver = Saver<ChatRule, String>(
    save = { AppJson.encodeToString(it) },
    restore = { AppJson.decodeFromString(it) },
)

/**
 * Edits one rule on a page of its own: what to look for, where, and what to do. A new rule
 * ([rule] null) starts from [ChatRule]'s defaults; an existing one keeps its id. Back leaves
 * without saving. The rule can be tried on a made-up message, among [savedRules], and on
 * [loadedMessages].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RuleEditorPage(
    rule: ChatRule?,
    savedRules: List<ChatRule>,
    loadedMessages: suspend () -> List<ChatItem>,
    onSave: (ChatRule) -> Unit,
    onBack: () -> Unit,
) {
    var draft by rememberSaveable(stateSaver = RuleSaver) {
        mutableStateOf(rule ?: ChatRule(id = UUID.randomUUID().toString(), pattern = ""))
    }
    val problem = remember(draft.pattern, draft.regex) {
        if (draft.regex) RuleEngine.problem(draft.pattern.trim()) else null
    }
    // A switched-off rule may keep a pattern still being worked on.
    val canSave = draft.pattern.isNotBlank() && !(draft.enabled && problem != null)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (rule == null) focus.requestFocus() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (rule == null) R.string.rule_new else R.string.rule_edit)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    TextButton(onClick = { onSave(draft.copy(pattern = draft.pattern.trim())) }, enabled = canSave) {
                        Text(stringResource(R.string.save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = draft.pattern,
                onValueChange = { draft = draft.copy(pattern = it) },
                singleLine = true,
                label = { Text(stringResource(R.string.rule_pattern)) },
                isError = problem != null,
                supportingText = {
                    Text(
                        stringResource(
                            when {
                                problem == PatternProblem.Broken -> R.string.rule_pattern_broken_hint
                                problem == PatternProblem.Unsupported -> R.string.rule_pattern_unsupported_hint
                                draft.regex -> R.string.rule_pattern_regex_hint
                                else -> R.string.rule_pattern_hint
                            },
                        ),
                    )
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
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

            // Hidden messages are never drawn, so no color.
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
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            RuleTry(draft, savedRules, loadedMessages)
            Spacer(Modifier.height(16.dp))
        }
    }
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

/**
 * A made-up message to try [rule] on, and the loaded messages the rule finds. Nothing is sent,
 * changed or notified.
 */
@Composable
private fun RuleTry(rule: ChatRule, savedRules: List<ChatRule>, loadedMessages: suspend () -> List<ChatItem>) {
    var text by rememberSaveable { mutableStateOf("") }
    var author by rememberSaveable { mutableStateOf("") }
    var inRuleChannel by rememberSaveable { mutableStateOf(true) }
    val ruleChannel = rule.channel

    Label(R.string.rule_try)
    Text(
        stringResource(R.string.rule_try_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        label = { Text(stringResource(R.string.rule_target_message)) },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    OutlinedTextField(
        value = author,
        onValueChange = { author = it },
        singleLine = true,
        label = { Text(stringResource(R.string.rule_target_author)) },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    if (ruleChannel != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            FilterChip(
                selected = inRuleChannel,
                onClick = { inRuleChannel = true },
                label = { Text(stringResource(R.string.rule_try_in_channel, ruleChannel)) },
            )
            FilterChip(
                selected = !inRuleChannel,
                onClick = { inRuleChannel = false },
                label = { Text(stringResource(R.string.rule_try_other_channel)) },
            )
        }
    }

    // A channel no rule can be limited to stands for "another channel".
    val channel = if (ruleChannel != null && inRuleChannel) ruleChannel else ""
    if (rule.pattern.isNotBlank() && (text.isNotBlank() || author.isNotBlank())) {
        val preview = remember(rule, savedRules, channel, author, text) {
            RulePreview.of(rule, savedRules, channel, author, text)
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    when (preview.outcome) {
                        is RuleOutcome.Unusable -> stringResource(R.string.rule_try_unusable)
                        RuleOutcome.NoMatch -> stringResource(R.string.rule_try_no_match)
                        RuleOutcome.OtherChannel -> stringResource(R.string.rule_try_other, ruleChannel.orEmpty())
                        RuleOutcome.Matches -> stringResource(R.string.rule_try_matches)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(
                        when (preview.result) {
                            RuleResult.Shown -> R.string.rule_try_shown
                            RuleResult.Highlighted -> R.string.rule_try_highlighted
                            RuleResult.Notifies -> R.string.rule_try_notifies
                            RuleResult.Hidden -> R.string.rule_try_hidden
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    val loaded by produceState(emptyList<ChatItem>()) { value = loadedMessages() }
    // Tried off the main thread: a regex over a few hundred messages on every keystroke.
    val found by produceState<List<ChatItem>?>(null, rule, loaded) {
        value = if (rule.pattern.isBlank() || loaded.isEmpty()) null
        else withContext(Dispatchers.Default) { RulePreview.matchesIn(rule, loaded) }
    }
    found?.let { matches ->
        Text(
            pluralStringResource(R.plurals.rule_try_found, matches.size, matches.size),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        matches.takeLast(FOUND_SHOWN).forEach { item ->
            Text(
                "${item.displayName ?: item.login}: ${item.text}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** How many of the loaded messages a rule finds are listed under the count. */
private const val FOUND_SHOWN = 3
