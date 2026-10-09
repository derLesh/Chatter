package dev.chatter.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chatter.app.R
import dev.chatter.app.chat.ChatRule
import dev.chatter.app.chat.RuleAction
import dev.chatter.app.chat.RuleEngine
import dev.chatter.app.chat.RuleTarget
import dev.chatter.app.net.HelixBlockedUser
import dev.chatter.app.settings.Settings
import dev.chatter.app.ui.MainViewModel
import dev.chatter.app.ui.SettingsSubPage

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
                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                colors = transparentItem(),
                modifier = Modifier.clickable { open(SettingsSubPage.Rules) },
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
                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                colors = transparentItem(),
                modifier = Modifier.clickable { open(SettingsSubPage.BlockedUsers) },
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
    var unblockTarget by remember { mutableStateOf<HelixBlockedUser?>(null) }
    var showBlock by remember { mutableStateOf(false) }

    SettingsGroup {
        if (blocked.isEmpty()) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_blocked_none)) },
                    supportingContent = { Text(stringResource(R.string.settings_blocked_hint)) },
                    colors = transparentItem(),
                )
            }
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_report_user)) },
                supportingContent = { Text(stringResource(R.string.settings_report_user_summary)) },
                leadingContent = { Icon(painterResource(R.drawable.ic_report_flag), contentDescription = null) },
                colors = transparentItem(),
            )
        }
        blocked.forEach { user ->
            item {
                ListItem(
                    headlineContent = { Text(user.displayName.ifEmpty { user.userLogin }) },
                    supportingContent = { Text("@" + user.userLogin) },
                    trailingContent = {
                        OutlinedButton(onClick = { unblockTarget = user }) {
                            Text(stringResource(R.string.action_unblock))
                        }
                    },
                    colors = transparentItem(),
                )
            }
        }
    }
    Button(onClick = { showBlock = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.block_user_title))
    }

    unblockTarget?.let { user ->
        ConfirmUnblockDialog(
            name = user.displayName.ifEmpty { user.userLogin },
            onConfirm = { vm.unblock(user) },
            onDismiss = { unblockTarget = null },
        )
    }
    if (showBlock) {
        BlockUserDialog(
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
 */
@Composable
internal fun RulesPage(vm: MainViewModel) {
    val rules by vm.rules.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ChatRule?>(null) }
    var creating by remember { mutableStateOf(false) }
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
            item {
                ListItem(
                    headlineContent = { Text(rule.pattern, fontFamily = if (rule.regex) FontFamily.Monospace else null) },
                    supportingContent = {
                        Column {
                            Text(ruleSummary(rule))
                            if (rule.regex && RuleEngine.skips(rule.pattern)) {
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
                            Switch(checked = rule.enabled, onCheckedChange = { vm.setRuleEnabled(rule, it) })
                            IconButton(onClick = { vm.deleteRule(rule) }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.rule_delete))
                            }
                        }
                    },
                    colors = transparentItem(),
                    modifier = Modifier.clickable { editing = rule },
                )
            }
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.rule_new)) },
                leadingContent = { CategoryIcon(Icons.Default.Add) },
                colors = transparentItem(),
                modifier = Modifier.clickable { creating = true },
            )
        }
    }

    if (creating || editing != null) {
        RuleDialog(
            rule = editing,
            onSave = vm::saveRule,
            onDismiss = { creating = false; editing = null },
        )
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
