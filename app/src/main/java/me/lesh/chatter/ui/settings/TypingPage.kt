package me.lesh.chatter.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import me.lesh.chatter.R
import me.lesh.chatter.chat.RoomState
import me.lesh.chatter.settings.RecentlySentButton
import me.lesh.chatter.settings.Settings
import me.lesh.chatter.ui.MainViewModel
import me.lesh.chatter.ui.chat.BuildStripe
import me.lesh.chatter.ui.chat.InputBar

/**
 * Writing messages: whether there is an input at all, suggestions as you type and how a mention is
 * written, and how the input looks, with a preview of it.
 */
@Composable
internal fun TypingPage(settings: Settings, vm: MainViewModel) {
    SettingsGroup {
        item(R.string.settings_read_only) { SwitchItem(R.string.settings_read_only, settings.readOnly, vm::setReadOnly, R.string.settings_read_only_hint) }
    }
    // Without an input there is nothing to suggest or arrange.
    if (settings.readOnly) return
    SettingsGroup(R.string.settings_group_suggestions) {
        item(R.string.settings_emote_suggestions) { SwitchItem(R.string.settings_emote_suggestions, settings.emoteSuggestions, vm::setEmoteSuggestions, R.string.settings_emote_suggestions_hint) }
        item(R.string.settings_user_suggestions) { SwitchItem(R.string.settings_user_suggestions, settings.userSuggestions, vm::setUserSuggestions, R.string.settings_user_suggestions_hint) }
        item(R.string.settings_bot_command_suggestions) {
            SwitchItem(R.string.settings_bot_command_suggestions, settings.botCommandSuggestions, vm::setBotCommandSuggestions, R.string.settings_bot_command_suggestions_hint)
        }
        item(R.string.settings_mention_with_at) { SwitchItem(R.string.settings_mention_with_at, settings.mentionWithAt, vm::setMentionWithAt, R.string.settings_mention_with_at_hint) }
    }
    SettingsGroup(R.string.settings_group_input) {
        item { Box(Modifier.padding(12.dp)) { InputPreview(settings, vm) } }
        item(R.string.settings_recently_sent) {
            SegmentedItem(
                R.string.settings_recently_sent, settings.recentlySentButton, RECENTLY_SENT_BUTTONS,
                vm::setRecentlySentButton, R.string.settings_recently_sent_hint,
            )
        }
        item(R.string.settings_chat_modes_button) {
            SwitchItem(R.string.settings_chat_modes_button, settings.chatModesButton, vm::setChatModesButton, R.string.settings_chat_modes_button_hint)
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
 * empties it, and the last messages are made up. The shield shows as for a mod, and its modes
 * change only here.
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
            onEmotePicker = {},
            onSend = { input = TextFieldValue("") },
            recentlySent = { sent },
            onReuseSent = { input = TextFieldValue(it, TextRange(it.length)) },
            recentlySentButton = settings.recentlySentButton,
            chatModes = modes.takeIf { settings.chatModesButton },
            onChatModes = { modes = it },
            buildStripe = settings.buildStripe,
        )
    }
}

private val RECENTLY_SENT_BUTTONS = listOf(
    RecentlySentButton.ReplacesSend to R.string.recently_sent_replaces_send,
    RecentlySentButton.InInput to R.string.recently_sent_in_input,
    RecentlySentButton.Hidden to R.string.recently_sent_hidden,
)
