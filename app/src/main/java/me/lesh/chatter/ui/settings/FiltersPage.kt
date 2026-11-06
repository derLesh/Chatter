package me.lesh.chatter.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.lesh.chatter.R
import me.lesh.chatter.chat.ChatRule
import me.lesh.chatter.chat.RuleAction
import me.lesh.chatter.chat.RuleEngine
import me.lesh.chatter.chat.RuleTarget
import me.lesh.chatter.settings.Settings
import me.lesh.chatter.ui.MainViewModel
import me.lesh.chatter.ui.SettingsSubPage

/** Everything that keeps something out of the chat, the Twitch block list included. */
@Composable
internal fun FiltersPage(settings: Settings, vm: MainViewModel, open: (SettingsSubPage) -> Unit) {
    val blocked by vm.blockedUsers.collectAsStateWithLifecycle()
    SettingsGroup {
        item(R.string.settings_mute_keywords) {
            KeywordListItem(
                title = R.string.settings_mute_keywords,
                summary = R.string.settings_mute_keywords_summary,
                words = settings.muteKeywords,
                onClick = { open(SettingsSubPage.MuteKeywords) },
            )
        }
        item(R.string.settings_rules) {
            val rules by vm.rules.collectAsStateWithLifecycle()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_rules)) },
                supportingContent = {
                    Text(
                        if (rules.isEmpty()) stringResource(R.string.settings_rules_summary)
                        else pluralStringResource(R.plurals.settings_rules_count, rules.size, rules.size)
                    )
                },
                trailingContent = { NavChevron() },
                colors = transparentItem(),
                modifier = Modifier.tileClickable { open(SettingsSubPage.Rules) },
            )
        }
        item(R.string.settings_blocked_users) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_blocked_users)) },
                supportingContent = {
                    Text(
                        if (blocked.isEmpty()) stringResource(R.string.settings_blocked_none)
                        else pluralStringResource(R.plurals.settings_blocked_count, blocked.size, blocked.size)
                    )
                },
                trailingContent = { NavChevron() },
                colors = transparentItem(),
                modifier = Modifier.tileClickable { open(SettingsSubPage.BlockedUsers) },
            )
        }
    }
}

/**
 * The Twitch block list: everyone here is hidden until unblocked. Unblocking asks for confirmation.
 */
@Composable
internal fun BlockedUsersPage(vm: MainViewModel) {
    val blocked by vm.blockedUsers.collectAsStateWithLifecycle()
    var unblockTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var showBlock by rememberSaveable { mutableStateOf(false) }

    SettingsGroup {
        // On top, so it is in reach without scrolling past everyone already blocked.
        // Blocking from the chat needs no trip here, so the row says how.
        item { AddItem(R.string.block_user_title, R.string.settings_report_user_summary) { showBlock = true } }
        if (blocked.isEmpty()) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_blocked_none)) },
                    supportingContent = { Text(stringResource(R.string.settings_blocked_hint)) },
                    colors = transparentItem(),
                )
            }
        }
        blocked.forEach { user ->
            item {
                ListItem(
                    headlineContent = { Text(user.displayName.ifEmpty { user.userLogin }) },
                    supportingContent = { Text("@" + user.userLogin) },
                    trailingContent = {
                        OutlinedButton(onClick = { unblockTargetId = user.userId }) {
                            Text(stringResource(R.string.action_unblock))
                        }
                    },
                    colors = transparentItem(),
                )
            }
        }
    }

    blocked.firstOrNull { it.userId == unblockTargetId }?.let { user ->
        ConfirmUnblockSheet(
            name = user.displayName.ifEmpty { user.userLogin },
            onConfirm = { vm.unblock(user) },
            onDismiss = { unblockTargetId = null },
        )
    }
    if (showBlock) {
        BlockUserSheet(
            search = vm::searchChannels,
            alreadyBlocked = blocked.mapTo(HashSet()) { it.userLogin.lowercase() },
            imageLoader = vm.imageLoader,
            onBlock = vm::blockByLogin,
            onDismiss = { showBlock = false },
        )
    }
}

/**
 * The user's highlight rules, listed in the order they apply, since a hide beats everything below.
 * [edit] opens a rule in the editor, or a new one for null.
 */
@Composable
internal fun RulesPage(vm: MainViewModel, edit: (ChatRule?) -> Unit) {
    val rules by vm.rules.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    SettingsGroup {
        if (rules.isEmpty()) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.rules_empty)) },
                    supportingContent = { Text(stringResource(R.string.rules_empty_hint)) },
                    colors = transparentItem(),
                )
            }
        }
        rules.forEach { rule ->
            val usable = !(rule.regex && RuleEngine.skips(rule.pattern))
            item {
                ListItem(
                    headlineContent = { Text(rule.pattern, fontFamily = if (rule.regex) FontFamily.Monospace else null) },
                    supportingContent = {
                        Column {
                            Text(ruleSummary(rule))
                            if (!usable) {
                                Text(stringResource(R.string.rule_unusable), color = scheme.error)
                            }
                        }
                    },
                    leadingContent = {
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(rule.color?.let { Color(it) } ?: scheme.surfaceContainerHighest),
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Off and greyed out while unusable; tapping the rule opens it to fix.
                            SettingsSwitch(
                                checked = rule.enabled && usable,
                                onCheckedChange = { vm.setRuleEnabled(rule, it) },
                                enabled = usable,
                            )
                            IconButton(onClick = { vm.deleteRule(rule) }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.rule_delete))
                            }
                        }
                    },
                    colors = transparentItem(),
                    modifier = Modifier.tileClickable { edit(rule) },
                )
            }
        }
        item { AddItem(R.string.rule_new) { edit(null) } }
    }
}

/** "Message - Highlight - #forsen": what a rule does, in one line. */
@Composable
private fun ruleSummary(rule: ChatRule): String {
    val target = stringResource(
        when (rule.target) {
            RuleTarget.Message -> R.string.rule_target_message
            RuleTarget.Author -> R.string.rule_target_author
            RuleTarget.Any -> R.string.rule_target_any
        }
    )
    val action = stringResource(
        when (rule.action) {
            RuleAction.Highlight -> R.string.rule_action_highlight
            RuleAction.Notify -> R.string.rule_action_notify
            RuleAction.Hide -> R.string.rule_action_hide
        }
    )
    return listOfNotNull(target, action, rule.channel?.let { "#$it" }).joinToString(" · ")
}
