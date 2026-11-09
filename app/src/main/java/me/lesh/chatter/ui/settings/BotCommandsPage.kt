package me.lesh.chatter.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
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

/** A command of a bot that has the same ones in every channel; null for a channel's own bot. */
private fun exampleOf(bot: Bot): String? = when (bot) {
    Bot.Supibot -> "\$ping"
    Bot.PotatBot -> "#ping"
    Bot.Fossabot, Bot.Nightbot, Bot.StreamElements, Bot.Streamlabs -> null
}

/** One switch per bot whose commands the input suggests; a bot turned off is never asked. */
@Composable
internal fun BotCommandsPage(settings: Settings, vm: MainViewModel) {
    SettingsGroup {
        item {
            Text(stringResource(R.string.settings_bot_commands_hint), modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
        Bot.entries.forEach { bot ->
            item {
                val on = bot in settings.botCommands
                val change = { turnOn: Boolean -> vm.setBotCommands(bot, turnOn) }
                ListItem(
                    headlineContent = { Text(stringResource(botName(bot))) },
                    supportingContent = {
                        val example = exampleOf(bot)
                        Text(
                            if (example != null) stringResource(R.string.settings_bot_everywhere_hint, stringResource(botName(bot)), example)
                            else stringResource(R.string.settings_bot_of_channel_hint),
                        )
                    },
                    trailingContent = { SettingsSwitch(checked = on, onCheckedChange = change) },
                    colors = transparentItem(),
                    modifier = Modifier.tileClickable { change(!on) },
                )
            }
        }
    }
}
