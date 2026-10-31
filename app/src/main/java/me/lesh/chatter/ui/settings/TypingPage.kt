package me.lesh.chatter.ui.settings

import androidx.compose.runtime.Composable
import me.lesh.chatter.R
import me.lesh.chatter.settings.Settings
import me.lesh.chatter.ui.MainViewModel

/**
 * Writing messages: whether there is an input at all, suggestions as you type and how a mention is
 * written.
 */
@Composable
internal fun TypingPage(settings: Settings, vm: MainViewModel) {
    SettingsGroup {
        item(R.string.settings_read_only) { SwitchItem(R.string.settings_read_only, settings.readOnly, vm::setReadOnly, R.string.settings_read_only_hint) }
        item(R.string.settings_emote_suggestions) { SwitchItem(R.string.settings_emote_suggestions, settings.emoteSuggestions, vm::setEmoteSuggestions, R.string.settings_emote_suggestions_hint) }
        item(R.string.settings_user_suggestions) { SwitchItem(R.string.settings_user_suggestions, settings.userSuggestions, vm::setUserSuggestions, R.string.settings_user_suggestions_hint) }
        item(R.string.settings_mention_with_at) { SwitchItem(R.string.settings_mention_with_at, settings.mentionWithAt, vm::setMentionWithAt, R.string.settings_mention_with_at_hint) }
    }
}
