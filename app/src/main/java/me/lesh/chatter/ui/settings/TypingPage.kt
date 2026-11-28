package me.lesh.chatter.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import me.lesh.chatter.R
import me.lesh.chatter.chat.Bot
import me.lesh.chatter.chat.RoomState
import me.lesh.chatter.settings.Settings
import me.lesh.chatter.ui.MainViewModel
import me.lesh.chatter.ui.SettingsSubPage
import me.lesh.chatter.ui.chat.BuildStripe
import me.lesh.chatter.ui.chat.InputBar

/**
 * Writing messages: whether there is an input at all, suggestions as you type and how a mention is
 * written, and how the input looks, with a preview of it.
 */
@Composable
internal fun TypingPage(settings: Settings, vm: MainViewModel, open: (SettingsSubPage) -> Unit) {
    SettingsGroup {
        item(R.string.settings_read_only) { SwitchItem(R.string.settings_read_only, settings.readOnly, vm::setReadOnly, R.string.settings_read_only_hint) }
    }
    // Without an input there is nothing to suggest or arrange.
    if (settings.readOnly) return
    SettingsGroup(R.string.settings_group_suggestions) {
        item(R.string.settings_emote_suggestions) { SwitchItem(R.string.settings_emote_suggestions, settings.emoteSuggestions, vm::setEmoteSuggestions, R.string.settings_emote_suggestions_hint) }
        item(R.string.settings_user_suggestions) { SwitchItem(R.string.settings_user_suggestions, settings.userSuggestions, vm::setUserSuggestions, R.string.settings_user_suggestions_hint) }
        item(R.string.settings_bot_commands) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_bot_commands)) },
                supportingContent = {
                    val on = settings.botCommands.size
                    val state = if (on == 0) stringResource(R.string.settings_off)
                    else stringResource(R.string.settings_bot_commands_on, on, Bot.entries.size)
                    Text("$BOT_COMMAND_EXAMPLES · $state")
                },
                trailingContent = { NavChevron() },
                colors = transparentItem(),
                modifier = Modifier.tileClickable { open(SettingsSubPage.BotCommands) },
            )
        }
        item(R.string.settings_mention_with_at) { SwitchItem(R.string.settings_mention_with_at, settings.mentionWithAt, vm::setMentionWithAt, R.string.settings_mention_with_at_hint) }
        item(R.string.settings_suggestions_over_chat) {
            BetaSwitchItem(R.string.settings_suggestions_over_chat, settings.suggestionsOverChat, vm::setSuggestionsOverChat, R.string.settings_suggestions_over_chat_hint)
        }
    }
    SettingsGroup(R.string.settings_group_input) {
        item { Box(Modifier.padding(12.dp)) { InputPreview(settings, vm) } }
        item(R.string.settings_reply_card) {
            BetaSwitchItem(R.string.settings_reply_card, settings.replyCard, vm::setReplyCard, R.string.settings_reply_card_hint)
        }
        if (BuildStripe.offered) {
            item(R.string.settings_build_stripe) {
                SwitchItem(R.string.settings_build_stripe, settings.buildStripe, vm::setBuildStripe, R.string.settings_build_stripe_hint)
            }
        }
    }
}

/**
 * The real input on the chat's background, to try out: what is typed stays here, sending only
 * empties it, and the last messages are made up. The menu has the chat modes as for a mod, and
 * they change only here.
 */
@Composable
private fun InputPreview(settings: Settings, vm: MainViewModel) {
    var input by remember { mutableStateOf(TextFieldValue("")) }
    val sent = stringArrayResource(R.array.input_preview_sent).toList()
    var modes by remember { mutableStateOf(RoomState()) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        InputBar(
            value = input,
            onValueChange = { input = it },
            enabled = true,
            replyTo = null,
            suggestions = emptyList(),
            imageLoader = vm.imageLoader,
            onSuggestion = {},
            onCancelReply = null,
            emotePicker = null,
            onSend = { input = TextFieldValue("") },
            recentlySent = { sent },
            onReuseSent = { input = TextFieldValue(it, TextRange(it.length)) },
            chatModes = modes,
            onChatModes = { modes = it },
            buildStripe = settings.buildStripe,
            // So the menu shows it; a tap here picks nothing.
            onPickPicture = {},
            // The rounded preview would cut off a menu lying above it.
            menuOverChat = false,
        )
    }
}

/** What the page is about, in the way it is typed; the same in every language. */
private const val BOT_COMMAND_EXAMPLES = "!discord, \$ping"
