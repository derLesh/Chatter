package me.lesh.chatter.ui.settings

import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.lesh.chatter.R
import me.lesh.chatter.settings.Settings
import me.lesh.chatter.settings.SpecialMessage
import me.lesh.chatter.settings.TapAction
import me.lesh.chatter.ui.MainViewModel
import me.lesh.chatter.ui.SettingsSubPage
import kotlin.math.roundToInt

/**
 * What the chat shows, how much of it is kept, what a tap does, the user card, and the screen while
 * reading.
 */
@Composable
internal fun MessagesPage(settings: Settings, vm: MainViewModel, open: (SettingsSubPage) -> Unit) {
    SettingsGroup(R.string.settings_group_in_chat) {
        item(R.string.settings_smooth_scrolling) {
            SwitchItem(
                R.string.settings_smooth_scrolling, settings.smoothScrolling,
                vm::setSmoothScrolling, R.string.settings_smooth_scrolling_hint,
            )
        }
        item(R.string.settings_show_deleted) { SwitchItem(R.string.settings_show_deleted, settings.showDeleted, vm::setShowDeleted, R.string.settings_show_deleted_hint) }
        item(R.string.settings_special_messages) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_special_messages)) },
                supportingContent = {
                    // Examples say what the page holds; a count alone did not.
                    val on = settings.specialMessages.size
                    val state = if (on == 0) stringResource(R.string.settings_off)
                    else stringResource(R.string.settings_special_messages_on, on, SpecialMessage.entries.size)
                    Text("${stringResource(R.string.settings_special_messages_examples)} · $state")
                },
                trailingContent = { NavChevron() },
                colors = transparentItem(),
                modifier = Modifier.tileClickable { open(SettingsSubPage.SpecialMessages) },
            )
        }
    }
    SettingsGroup(R.string.settings_group_history) {
        item(R.string.settings_message_limit) {
            var limit by remember(settings.messageLimit) { mutableFloatStateOf(settings.messageLimit.toFloat()) }
            SliderItem(
                title = R.string.settings_message_limit,
                valueLabel = limit.roundToInt().toString(),
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
        item(R.string.settings_user_card_buttons_first) {
            SwitchItem(
                R.string.settings_user_card_buttons_first, settings.userCardButtonsFirst,
                vm::setUserCardButtonsFirst, R.string.settings_user_card_buttons_first_hint,
            )
        }
        item(R.string.settings_copy_first) { SwitchItem(R.string.settings_copy_first, settings.copyFirst, vm::setCopyFirst) }
        item(R.string.settings_user_card_attachments) {
            SwitchItem(
                R.string.settings_user_card_attachments, settings.userCardAttachments,
                vm::setUserCardAttachments, R.string.settings_user_card_attachments_hint,
            )
        }
    }
    SettingsGroup(R.string.settings_group_screen) {
        item(R.string.settings_keep_screen_on) {
            SwitchItem(
                R.string.settings_keep_screen_on, settings.keepScreenOn,
                vm::setKeepScreenOn, R.string.settings_keep_screen_on_hint,
            )
        }
        // Only relevant while the screen is kept on.
        if (settings.keepScreenOn) {
            item(R.string.settings_keep_screen_on_when_quiet) {
                SwitchItem(
                    R.string.settings_keep_screen_on_when_quiet, settings.keepScreenOnWhenQuiet,
                    vm::setKeepScreenOnWhenQuiet, R.string.settings_keep_screen_on_when_quiet_hint,
                )
            }
        }
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
