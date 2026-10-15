package dev.chatter.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.chatter.app.R
import dev.chatter.app.settings.Settings
import dev.chatter.app.settings.TapAction
import dev.chatter.app.ui.MainViewModel
import kotlin.math.roundToInt

/** How much of a chat is kept and loaded, and what tapping a message does. */
@Composable
internal fun MessagesPage(settings: Settings, vm: MainViewModel) {
    SettingsGroup(R.string.settings_group_history) {
        item(R.string.settings_message_limit) {
            var limit by remember(settings.messageLimit) { mutableFloatStateOf(settings.messageLimit.toFloat()) }
            SliderItem(
                title = stringResource(R.string.settings_message_limit, limit.roundToInt()),
                value = limit,
                onChange = { limit = it },
                onDone = { vm.setMessageLimit(limit.roundToInt()) },
                range = 100f..2000f,
                steps = 18,
            )
        }
        item(R.string.settings_load_history) { SwitchItem(R.string.settings_load_history, settings.loadHistory, vm::setLoadHistory, R.string.settings_load_history_hint) }
    }
    SettingsGroup(R.string.settings_group_controls) {
        item(R.string.settings_message_tap) { TapActionPicker(R.string.settings_message_tap, settings.messageTap, vm::setMessageTap) }
        item(R.string.settings_name_tap) { TapActionPicker(R.string.settings_name_tap, settings.nameTap, vm::setNameTap) }
    }
    SettingsGroup(R.string.settings_group_user_card) {
        item(R.string.settings_copy_first) { SwitchItem(R.string.settings_copy_first, settings.copyFirst, vm::setCopyFirst, R.string.settings_copy_first_hint) }
    }
}

/**
 * What a tap on a message or its name does. The hint points out that holding always opens the user
 * card, with blocking, reporting and moderating.
 */
@Composable
private fun TapActionPicker(title: Int, selected: TapAction, onSelect: (TapAction) -> Unit) {
    ChoiceItem(
        title = title,
        value = selected,
        options = TapAction.entries,
        label = { action ->
            stringResource(
                when (action) {
                    TapAction.Reply -> R.string.tap_action_reply
                    TapAction.UserCard -> R.string.tap_action_user_card
                    TapAction.Mention -> R.string.tap_action_mention
                    TapAction.Nothing -> R.string.tap_action_nothing
                },
            )
        },
        onPick = onSelect,
        hint = R.string.settings_tap_hint,
    )
}
