package dev.chatter.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.chatter.app.R
import dev.chatter.app.badges.BadgeProvider
import dev.chatter.app.emotes.EmoteProvider
import dev.chatter.app.emotes.label
import dev.chatter.app.settings.Settings
import dev.chatter.app.ui.MainViewModel
import dev.chatter.app.ui.SettingsSubPage

/** Emotes and badges in one place, providers included. */
@Composable
internal fun EmotesPage(settings: Settings, vm: MainViewModel, open: (SettingsSubPage) -> Unit) {
    SettingsGroup(R.string.settings_group_emotes) {
        item(R.string.settings_emotes_enabled) { SwitchItem(R.string.settings_emotes_enabled, settings.emotesEnabled, vm::setEmotesEnabled, R.string.settings_emotes_new_messages_hint) }
        item(R.string.settings_animated_emotes) { SwitchItem(R.string.settings_animated_emotes, settings.animatedEmotes, vm::setAnimatedEmotes) }
        // Only relevant while they animate.
        if (settings.animatedEmotes) {
            item(R.string.settings_slow_idle_emotes) {
                SwitchItem(R.string.settings_slow_idle_emotes, settings.slowIdleEmotes, vm::setSlowIdleEmotes, R.string.settings_slow_idle_emotes_hint)
            }
        }
        item(R.string.settings_zero_width) { SwitchItem(R.string.settings_zero_width, settings.zeroWidthEmotes, vm::setZeroWidthEmotes, R.string.settings_zero_width_hint) }
        item(R.string.settings_unlisted_7tv) { SwitchItem(R.string.settings_unlisted_7tv, settings.showUnlisted7tv, vm::setShowUnlisted7tv, R.string.settings_unlisted_7tv_hint) }
        item(R.string.settings_seventv_events) { SwitchItem(R.string.settings_seventv_events, settings.sevenTvEvents, vm::setSevenTvEvents, R.string.settings_seventv_events_hint) }
        item(R.string.settings_seventv_paints) { SwitchItem(R.string.settings_seventv_paints, settings.sevenTvPaints, vm::setSevenTvPaints, R.string.settings_seventv_paints_hint) }
        item(R.string.settings_hidden_emotes) {
            val hidden by vm.hiddenEmotes.collectAsStateWithLifecycle()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_hidden_emotes)) },
                supportingContent = {
                    Text(
                        if (hidden.isEmpty()) stringResource(R.string.settings_hidden_emotes_none)
                        else pluralStringResource(R.plurals.settings_hidden_emotes_count, hidden.list.size, hidden.list.size)
                    )
                },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                colors = transparentItem(),
                modifier = Modifier.clickable { open(SettingsSubPage.HiddenEmotes) },
            )
        }
    }
    SettingsGroup(R.string.settings_emote_providers) {
        PROVIDERS.forEach { (provider, label) ->
            item { SwitchItem(label, provider in settings.emoteProviders, { vm.setEmoteProvider(provider, it) }) }
        }
    }
    SettingsGroup(R.string.settings_badge_providers) {
        BADGE_PROVIDERS.forEach { (provider, label) ->
            item { SwitchItem(label, provider in settings.badgeProviders, { vm.setBadgeProvider(provider, it) }) }
        }
    }
}

/** The emotes the user hid, each with where it is hidden and a button to show it again. */
@Composable
internal fun HiddenEmotesPage(vm: MainViewModel) {
    val hidden by vm.hiddenEmotes.collectAsStateWithLifecycle()
    SettingsGroup {
        if (hidden.isEmpty()) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_hidden_emotes_none)) },
                    supportingContent = { Text(stringResource(R.string.settings_hidden_emotes_hint)) },
                    colors = transparentItem(),
                )
            }
        }
        hidden.list.sortedBy { it.name.lowercase() }.forEach { emote ->
            item {
                ListItem(
                    headlineContent = { Text(emote.name) },
                    supportingContent = {
                        val where = emote.channel?.let { stringResource(R.string.emote_hide_in_channel, it) }
                            ?: stringResource(R.string.emote_hide_everywhere)
                        Text("${emote.provider.label} · $where")
                    },
                    leadingContent = {
                        AsyncImage(model = emote.url, contentDescription = null, imageLoader = vm.imageLoader, modifier = Modifier.size(32.dp))
                    },
                    trailingContent = {
                        IconButton(onClick = { vm.showEmote(emote) }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_hidden_emotes_show, emote.name))
                        }
                    },
                    colors = transparentItem(),
                )
            }
        }
    }
}

/** Badge providers in the order their badges appear before a name. */
internal val BADGE_PROVIDERS = listOf(
    BadgeProvider.Twitch to R.string.settings_provider_twitch,
    BadgeProvider.SevenTv to R.string.settings_provider_seventv,
    BadgeProvider.Chatterino to R.string.settings_provider_chatterino,
    BadgeProvider.Chatter to R.string.settings_provider_chatter,
)

/** Emote providers in order of precedence. */
internal val PROVIDERS = listOf(
    EmoteProvider.Twitch to R.string.settings_provider_twitch,
    EmoteProvider.SevenTv to R.string.settings_provider_seventv,
    EmoteProvider.Bttv to R.string.settings_provider_bttv,
    EmoteProvider.Ffz to R.string.settings_provider_ffz,
)
