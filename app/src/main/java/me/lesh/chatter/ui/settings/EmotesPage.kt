package me.lesh.chatter.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import me.lesh.chatter.R
import me.lesh.chatter.badges.Badge
import me.lesh.chatter.badges.BadgeProvider
import me.lesh.chatter.ui.chat.badgeKindName
import me.lesh.chatter.ui.chat.badgePicture
import me.lesh.chatter.ui.chat.isWellKnownBadge
import me.lesh.chatter.emotes.EmoteProvider
import me.lesh.chatter.emotes.label
import me.lesh.chatter.settings.Settings
import me.lesh.chatter.ui.MainViewModel
import me.lesh.chatter.ui.SettingsSubPage

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
        item(R.string.settings_badge_kinds) {
            val hidden by vm.hiddenBadges.collectAsStateWithLifecycle()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_badge_kinds)) },
                supportingContent = {
                    Text(
                        if (hidden.isEmpty()) stringResource(R.string.settings_badge_kinds_none)
                        else pluralStringResource(R.plurals.settings_badge_kinds_hidden, hidden.size, hidden.size)
                    )
                },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                colors = transparentItem(),
                modifier = Modifier.clickable { open(SettingsSubPage.BadgeKinds) },
            )
        }
    }
}

/**
 * Every kind of badge Chatter has loaded, Twitch's whole global list included, each with a switch.
 * Without a search only hidden and well-known kinds are listed: all of them would be hundreds of
 * rows, and settings pages draw every row at once.
 */
@Composable
internal fun BadgeKindsPage(vm: MainViewModel) {
    val hidden by vm.hiddenBadges.collectAsStateWithLifecycle()
    val hiddenKinds by vm.hiddenBadgeKinds.collectAsStateWithLifecycle()
    // Read once, and sorted only by what was hidden on opening: a switch must not move its row.
    val known = remember { vm.knownBadgeKinds() }
    val hiddenOnOpen = remember { hiddenKinds }
    var query by rememberSaveable { mutableStateOf("") }
    val rows = (hidden.map { BadgeKindRow(it.kind, it.provider, it.set, it.title, it.url, it.urlOnLight) } +
        known.map { BadgeKindRow(it.kind, it.provider, it.set, it.title, it.url, it.urlOnLight) })
        .distinctBy { it.kind }
    val search = query.trim()
    val matches = rows.map { it to badgeKindName(it.provider, it.set, it.title) }
        .filter { (row, name) ->
            if (search.isEmpty()) row.kind in hiddenOnOpen || isWellKnownBadge(row.provider, row.set)
            else name.contains(search, ignoreCase = true) || row.title.contains(search, ignoreCase = true)
        }
        .sortedWith(compareBy({ it.first.kind !in hiddenOnOpen }, { it.first.provider.ordinal }, { it.second.lowercase() }))
    val named = matches.take(MAX_BADGE_ROWS)
    val more = (if (search.isEmpty()) rows.size else matches.size) - named.size
    SettingsGroup {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(stringResource(if (rows.isEmpty()) R.string.settings_badge_kinds_empty else R.string.settings_badge_kinds_hint))
                if (rows.isNotEmpty()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.settings_badge_kinds_search)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            }
        }
        named.forEach { (row, name) ->
            item {
                val shown = row.kind !in hiddenKinds
                val change = { show: Boolean -> if (show) vm.showBadge(row.kind) else vm.hideBadge(row.toBadge()) }
                ListItem(
                    headlineContent = { Text(name) },
                    supportingContent = { Text(stringResource(BADGE_PROVIDERS.first { it.first == row.provider }.second)) },
                    leadingContent = {
                        AsyncImage(
                            model = badgePicture(row.url, row.urlOnLight),
                            contentDescription = null,
                            imageLoader = vm.imageLoader,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    trailingContent = { Switch(checked = shown, onCheckedChange = change) },
                    colors = transparentItem(),
                    modifier = Modifier.clickable { change(!shown) },
                )
            }
        }
        if (more > 0) {
            item {
                ListItem(
                    headlineContent = { Text(pluralStringResource(R.plurals.settings_badge_kinds_more, more, more)) },
                    colors = transparentItem(),
                )
            }
        }
    }
}

private const val MAX_BADGE_ROWS = 50

private class BadgeKindRow(
    val kind: String,
    val provider: BadgeProvider,
    val set: String,
    val title: String,
    val url: String?,
    val urlOnLight: String?,
) {
    fun toBadge() = Badge(url.orEmpty(), title, provider, set, urlOnLight)
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
