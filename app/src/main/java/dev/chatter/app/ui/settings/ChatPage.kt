package dev.chatter.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chatter.app.R
import dev.chatter.app.chat.ImageLinks
import dev.chatter.app.settings.MobileData
import dev.chatter.app.settings.Settings
import dev.chatter.app.settings.TapAction
import dev.chatter.app.ui.MainViewModel
import dev.chatter.app.ui.SettingsSubPage
import kotlin.math.roundToInt

@Composable
internal fun ChatPage(settings: Settings, vm: MainViewModel, open: (SettingsSubPage) -> Unit) {
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
    SettingsGroup(R.string.settings_group_images) {
        item(R.string.settings_inline_images) {
            SwitchItem(
                R.string.settings_inline_images, settings.inlineImages,
                vm::setInlineImages, R.string.settings_inline_images_hint,
            )
        }
        item(R.string.settings_image_hosts) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_image_hosts)) },
                supportingContent = {
                    Text(
                        if (settings.imageHosts.isEmpty()) stringResource(R.string.settings_image_hosts_none)
                        else pluralStringResource(
                            R.plurals.settings_image_hosts_count,
                            settings.imageHosts.size,
                            settings.imageHosts.size,
                        )
                    )
                },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                colors = transparentItem(),
                modifier = Modifier.clickable { open(SettingsSubPage.ImageHosts) },
            )
        }
        item(R.string.settings_full_links) { SwitchItem(R.string.settings_full_links, settings.fullLinks, vm::setFullLinks, R.string.settings_full_links_hint) }
        item(R.string.settings_mobile_data) { MobileDataPicker(settings.mobileData, vm) }
    }
    // Input assistance.
    SettingsGroup(R.string.settings_group_input) {
        item(R.string.settings_emote_suggestions) { SwitchItem(R.string.settings_emote_suggestions, settings.emoteSuggestions, vm::setEmoteSuggestions, R.string.settings_emote_suggestions_hint) }
        item(R.string.settings_user_suggestions) { SwitchItem(R.string.settings_user_suggestions, settings.userSuggestions, vm::setUserSuggestions, R.string.settings_user_suggestions_hint) }
        item(R.string.settings_mention_with_at) { SwitchItem(R.string.settings_mention_with_at, settings.mentionWithAt, vm::setMentionWithAt, R.string.settings_mention_with_at_hint) }
    }
    // What a tap or swipe on the chat does.
    SettingsGroup(R.string.settings_group_controls) {
        item(R.string.settings_message_tap) { TapActionPicker(R.string.settings_message_tap, settings.messageTap, vm::setMessageTap) }
        item(R.string.settings_name_tap) { TapActionPicker(R.string.settings_name_tap, settings.nameTap, vm::setNameTap) }
        item(R.string.settings_carousel) {
            SwitchItem(
                R.string.settings_carousel, settings.carouselChannels,
                vm::setCarouselChannels, R.string.settings_carousel_hint,
            )
        }
    }
    SettingsGroup(R.string.settings_group_user_card) {
        item(R.string.settings_copy_first) { SwitchItem(R.string.settings_copy_first, settings.copyFirst, vm::setCopyFirst, R.string.settings_copy_first_hint) }
    }
}

internal val MOBILE_DATA = listOf(
    MobileData.Normal to R.string.mobile_data_normal,
    MobileData.SaveData to R.string.mobile_data_save,
)

/**
 * Behaviour on mobile data. The row says when Data Saver is on, since the phone saves data then
 * regardless of the choice here.
 */
@Composable
private fun MobileDataPicker(selected: MobileData, vm: MainViewModel) {
    val saving by vm.saveData.collectAsStateWithLifecycle()
    ChoiceItem(
        title = R.string.settings_mobile_data,
        value = selected,
        options = MOBILE_DATA.map { it.first },
        label = { choice ->
            val name = stringResource(MOBILE_DATA.first { it.first == choice }.second)
            if (saving && choice == MobileData.Normal) stringResource(R.string.mobile_data_saver_on, name) else name
        },
        onPick = vm::setMobileData,
        hint = R.string.settings_mobile_data_hint,
    )
}

/**
 * The sites linked images are loaded from. Nothing else is ever requested, so the page explains the
 * list first.
 */
@Composable
internal fun ImageHostsPage(hosts: List<String>, vm: MainViewModel) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }

    Text(
        stringResource(R.string.settings_image_hosts_note),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    )
    SettingsGroup {
        if (hosts.isEmpty()) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_image_hosts_none)) },
                    supportingContent = { Text(stringResource(R.string.settings_image_hosts_hint)) },
                    colors = transparentItem(),
                )
            }
        }
        hosts.forEach { host ->
            item {
                ListItem(
                    headlineContent = { Text(host) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { editing = host }) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.settings_image_hosts_edit_one, host))
                            }
                            IconButton(onClick = { vm.removeImageHost(host) }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.keyword_remove, host))
                            }
                        }
                    },
                    colors = transparentItem(),
                )
            }
        }
    }
    Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.settings_image_hosts_add))
    }
    if (hosts != ImageLinks.DEFAULT_HOSTS) {
        OutlinedButton(onClick = vm::resetImageHosts, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_image_hosts_reset))
        }
    }

    if (adding) {
        AddKeywordDialog(
            title = R.string.settings_image_hosts_add,
            hint = R.string.settings_image_hosts_hint,
            onAdd = vm::addImageHost,
            onDismiss = { adding = false },
            label = R.string.settings_image_hosts_site,
        )
    }
    editing?.let { host ->
        AddKeywordDialog(
            title = R.string.settings_image_hosts_edit,
            hint = R.string.settings_image_hosts_hint,
            onAdd = { vm.editImageHost(host, it) },
            onDismiss = { editing = null },
            initial = host,
            label = R.string.settings_image_hosts_site,
            confirmLabel = R.string.save,
        )
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
