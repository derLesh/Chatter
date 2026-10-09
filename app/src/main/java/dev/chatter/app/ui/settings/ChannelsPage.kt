package dev.chatter.app.ui.settings

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chatter.app.R
import dev.chatter.app.service.ChatNotifier
import dev.chatter.app.settings.Settings
import dev.chatter.app.ui.MainViewModel
import dev.chatter.app.ui.channels.AddChannelDialog
import dev.chatter.app.ui.channels.CombineChannelsDialog
import dev.chatter.app.ui.channels.ManageChannelsPage
import dev.chatter.app.ui.channels.RenameChannelDialog

@Composable
internal fun ChannelsPage(vm: MainViewModel, settings: Settings) {
    val context = LocalContext.current
    val channels by vm.channels.collectAsStateWithLifecycle()
    val pages by vm.pages.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val info by vm.channelInfo.collectAsStateWithLifecycle()
    val customNames by vm.customNames.collectAsStateWithLifecycle()
    val muted by vm.mutedChannels.collectAsStateWithLifecycle()
    val hiddenUnread by vm.hiddenUnread.collectAsStateWithLifecycle()
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    // Null while no dialog is open; the key of the combined chat being edited, or "" for a new one.
    var combineTarget by remember { mutableStateOf<String?>(null) }

    ManageChannelsPage(
        pages = pages,
        groups = groups,
        info = info,
        muted = muted,
        hiddenUnread = hiddenUnread,
        imageLoader = vm.imageLoader,
        onMove = vm::moveChannel,
        onNotify = vm::setChannelNotify,
        onNotificationSettings = { login ->
            context.startActivity(
                Intent(AndroidSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(AndroidSettings.EXTRA_CHANNEL_ID, ChatNotifier.mentionChannelId(login))
            )
        },
        onUnreadVisible = vm::setChannelUnreadVisible,
        onRename = { renameTarget = it },
        onRemove = vm::removeChannel,
        onAdd = { showAdd = true },
        onCombine = { combineTarget = "" },
        onEditGroup = { combineTarget = it },
    )
    SettingsGroup {
        item(R.string.settings_channel_tabs) {
            SwitchItem(
                R.string.settings_channel_tabs,
                settings.channelTabs,
                { vm.setChannelTabs(it) },
                R.string.settings_channel_tabs_hint,
            )
        }
        item(R.string.settings_unread_title_bar) {
            SwitchItem(
                R.string.settings_unread_title_bar,
                settings.unreadInTitleBar,
                { vm.setUnreadInTitleBar(it) },
                R.string.settings_unread_title_bar_hint,
            )
        }
    }
    renameTarget?.let { login ->
        RenameChannelDialog(
            login = login,
            currentName = customNames[login].orEmpty(),
            twitchName = vm.twitchName(login),
            onRename = { vm.renameChannel(login, it) },
            onDismiss = { renameTarget = null },
        )
    }
    if (showAdd) {
        AddChannelDialog(
            search = vm::searchChannels,
            imageLoader = vm.imageLoader,
            onAdd = { vm.addChannel(it); showAdd = false },
            onDismiss = { showAdd = false },
        )
    }
    combineTarget?.let { key ->
        val group = groups[key]
        CombineChannelsDialog(
            channels = channels,
            info = info,
            group = group,
            imageLoader = vm.imageLoader,
            onSave = { name, members -> vm.saveGroup(group?.key, name, members) },
            onDismiss = { combineTarget = null },
        )
    }
}
