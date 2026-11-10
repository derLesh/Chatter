package me.lesh.chatter.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.lesh.chatter.R
import me.lesh.chatter.chat.Bot
import me.lesh.chatter.settings.Settings
import me.lesh.chatter.ui.MainViewModel
import me.lesh.chatter.ui.chat.botName

/** One of the bot's own commands, to show what typing for it looks like. */
private fun exampleOf(bot: Bot): String? = when (bot) {
    Bot.Supibot -> "\$ping"
    Bot.PotatBot -> "#ping"
    Bot.Fossabot, Bot.Moobot, Bot.Nightbot, Bot.StreamElements, Bot.Streamlabs -> null
}

/**
 * One switch per bot whose commands the input suggests; a bot turned off is never asked. Bots with
 * commands of their own are apart from those that only have what each channel set up.
 */
@Composable
internal fun BotCommandsPage(settings: Settings, vm: MainViewModel) {
    Text(
        stringResource(R.string.settings_bot_commands_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    val (everywhere, ofChannel) = Bot.entries.partition { it.sameEverywhere }
    SettingsGroup(R.string.settings_bot_group_everywhere) {
        everywhere.forEach { bot -> item { BotItem(bot, bot in settings.botCommands) { vm.setBotCommands(bot, it) } } }
    }
    SettingsGroup(R.string.settings_bot_group_channel) {
        ofChannel.forEach { bot -> item { BotItem(bot, bot in settings.botCommands) { vm.setBotCommands(bot, it) } } }
    }
}

@Composable
private fun BotItem(bot: Bot, on: Boolean, onChange: (Boolean) -> Unit) {
    val example = exampleOf(bot)
    ListItem(
        headlineContent = { Text(stringResource(botName(bot))) },
        supportingContent = example?.let { { Text(stringResource(R.string.settings_bot_example, it)) } },
        trailingContent = { SettingsSwitch(checked = on, onCheckedChange = onChange) },
        colors = transparentItem(),
        modifier = Modifier.tileClickable { onChange(!on) },
    )
}
