package dev.chatter.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chatter.app.R
import dev.chatter.app.net.HelixFollowedChannel
import dev.chatter.app.settings.Settings
import dev.chatter.app.ui.MainViewModel
import dev.chatter.app.ui.SettingsSubPage
import dev.chatter.app.ui.channels.ChannelAvatar
import dev.chatter.app.ui.notificationsAllowed
import dev.chatter.app.ui.openNotificationSettings

@Composable
internal fun NotificationsPage(settings: Settings, vm: MainViewModel, open: (SettingsSubPage) -> Unit) {
    val context = LocalContext.current
    val stop by vm.backgroundStop.collectAsStateWithLifecycle()
    val battery by vm.batteryRestrictions.collectAsStateWithLifecycle()
    var notificationsOn by remember { mutableStateOf(notificationsAllowed(context)) }
    // The cards' buttons lead into the system settings; check again on return.
    LifecycleResumeEffect(Unit) {
        vm.refreshBatteryRestrictions()
        notificationsOn = notificationsAllowed(context)
        onPauseOrDispose { }
    }
    if (!notificationsOn) NotificationsOffCard()
    BackgroundCard(stop, battery, vm::dismissBackgroundStop)
    SettingsGroup(R.string.settings_group_mentions) {
        item(R.string.settings_keywords) {
            KeywordListItem(
                title = R.string.settings_keywords,
                summary = R.string.settings_keywords_summary,
                words = settings.mentionKeywords,
                onClick = { open(SettingsSubPage.MentionKeywords) },
            )
        }
        item(R.string.settings_sender_avatars) {
            SwitchItem(
                R.string.settings_sender_avatars, settings.senderAvatars,
                vm::setSenderAvatars, R.string.settings_sender_avatars_hint,
            )
        }
        item(R.string.settings_system_notifications) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_system_notifications)) },
                supportingContent = { Text(stringResource(R.string.settings_notifications_hint)) },
                trailingContent = {
                    OutlinedButton(onClick = { openNotificationSettings(context) }) { Text(stringResource(R.string.open)) }
                },
                colors = transparentItem(),
            )
        }
    }
    SettingsGroup(R.string.settings_group_live) {
        item(R.string.settings_live_notifications) {
            SwitchItem(R.string.settings_live_notifications, settings.liveNotifications, vm::setLiveNotifications, R.string.settings_live_notifications_hint)
        }
        // Without the switch the choice per channel changes nothing.
        if (settings.liveNotifications) {
            item(R.string.settings_live_channels) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_live_channels)) },
                    supportingContent = { Text(stringResource(R.string.settings_live_channels_summary)) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                    colors = transparentItem(),
                    modifier = Modifier.clickable { open(SettingsSubPage.LiveChannels) },
                )
            }
        }
    }
    SettingsGroup(R.string.settings_group_bubbles) {
        item(R.string.settings_bubbles) { SwitchItem(R.string.settings_bubbles, settings.bubbles, vm::setBubbles, R.string.settings_bubbles_hint) }
    }
}

/** Which channels notify when they go live; see [dev.chatter.app.channels.LiveAlertChoices]. */
@Composable
internal fun LiveChannelsPage(vm: MainViewModel) {
    val channels by vm.channels.collectAsStateWithLifecycle()
    val info by vm.channelInfo.collectAsStateWithLifecycle()
    val choices by vm.liveAlerts.collectAsStateWithLifecycle()
    // Null while loading; empty if Twitch did not answer or the account follows nobody.
    var follows by remember { mutableStateOf<List<HelixFollowedChannel>?>(null) }
    LaunchedEffect(Unit) { follows = vm.followedChannelList().orEmpty().sortedBy { it.displayName.lowercase() } }

    if (channels.isNotEmpty()) SettingsGroup(R.string.live_channels_in_list) {
        channels.forEach { login ->
            item {
                LiveAlertSwitch(info[login]?.displayName ?: login, choices.wanted(login, inList = true), { vm.setLiveAlert(login, it) }) {
                    ChannelAvatar(info[login], vm.imageLoader, 40.dp)
                }
            }
        }
    }
    SettingsGroup(R.string.live_channels_followed) {
        val others = follows?.filter { it.login !in channels }
        when {
            others == null -> item {
                ListItem(headlineContent = { Text(stringResource(R.string.live_channels_loading)) }, colors = transparentItem())
            }
            others.isEmpty() -> item {
                ListItem(headlineContent = { Text(stringResource(R.string.live_channels_none)) }, colors = transparentItem())
            }
            else -> others.forEach { follow ->
                item {
                    LiveAlertSwitch(follow.displayName.ifEmpty { follow.login }, choices.wanted(follow.login, inList = false), {
                        vm.setLiveAlert(follow.login, it)
                    })
                }
            }
        }
    }
}

/** A channel on the live notification page; followed channels come without a picture. */
@Composable
private fun LiveAlertSwitch(name: String, on: Boolean, onChange: (Boolean) -> Unit, avatar: (@Composable () -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(name) },
        leadingContent = avatar,
        trailingContent = { Switch(checked = on, onCheckedChange = onChange) },
        colors = transparentItem(),
        modifier = Modifier.clickable { onChange(!on) },
    )
}
