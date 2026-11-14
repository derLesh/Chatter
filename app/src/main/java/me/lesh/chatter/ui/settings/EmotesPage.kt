package me.lesh.chatter.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import me.lesh.chatter.R
import me.lesh.chatter.badges.Badge
import me.lesh.chatter.badges.BadgeProvider
import me.lesh.chatter.emotes.EmoteProvider
import me.lesh.chatter.emotes.label
import me.lesh.chatter.settings.Settings
import me.lesh.chatter.ui.MainViewModel
import me.lesh.chatter.ui.SettingsSubPage
import me.lesh.chatter.ui.chat.badgeBackground
import me.lesh.chatter.ui.chat.badgeKindName
import me.lesh.chatter.ui.chat.badgePicture
import me.lesh.chatter.ui.chat.isWellKnownBadge

/** Emotes and badges in one place: one entry per provider, each opening its own page, and what holds for all of them. */
@Composable
internal fun EmotesPage(settings: Settings, vm: MainViewModel, open: (SettingsSubPage) -> Unit) {
    val hiddenBadges by vm.hiddenBadges.collectAsStateWithLifecycle()
    SettingsGroup(R.string.settings_providers, hint = R.string.settings_providers_hint) {
        Provider.entries.forEach { provider ->
            item(provider.title) {
                val hidden = provider.badges?.let { badges -> hiddenBadges.count { it.provider == badges } } ?: 0
                ListItem(
                    headlineContent = { Text(stringResource(provider.title)) },
                    supportingContent = {
                        val summary = stringResource(providerSummary(provider, settings))
                        Text(if (hidden == 0) summary else summary + " · " + pluralStringResource(R.plurals.settings_provider_badges_hidden, hidden, hidden))
                    },
                    leadingContent = { ProviderLogo(provider) },
                    trailingContent = { NavChevron() },
                    colors = transparentItem(),
                    modifier = Modifier.tileClickable { open(providerPage(provider)) },
                )
            }
        }
    }
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
                trailingContent = { NavChevron() },
                colors = transparentItem(),
                modifier = Modifier.tileClickable { open(SettingsSubPage.HiddenEmotes) },
            )
        }
    }
}

@Composable
private fun ProviderLogo(provider: Provider) {
    Icon(
        painterResource(provider.logo),
        contentDescription = null,
        tint = provider.tint ?: MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(24.dp),
    )
}

/** Emotes count as off while all emotes are, since none of the provider's show then. */
private fun providerSummary(provider: Provider, settings: Settings): Int {
    val emotes = provider.emotes != null && provider.emotes in settings.emoteProvidersOn
    val badges = provider.badges != null && provider.badges in settings.badgeProviders
    return when {
        provider.emotes == null || provider.badges == null -> if (emotes || badges) R.string.settings_provider_on else R.string.settings_off
        emotes && badges -> R.string.settings_provider_emotes_and_badges
        emotes -> R.string.settings_provider_only_emotes
        badges -> R.string.settings_provider_only_badges
        else -> R.string.settings_off
    }
}

/** One provider: whether its emotes and badges show, which of its badges do, and for 7TV what else it brings. */
@Composable
internal fun ProviderPage(provider: Provider, settings: Settings, vm: MainViewModel) {
    SettingsGroup {
        provider.emotes?.let { emotes ->
            item(R.string.settings_provider_emotes) {
                val hint = if (settings.emotesEnabled) R.string.settings_provider_emotes_hint else R.string.settings_provider_emotes_all_off
                SwitchItem(R.string.settings_provider_emotes, emotes in settings.emoteProviders, { vm.setEmoteProvider(emotes, it) }, hint)
            }
        }
        provider.badges?.let { badges ->
            item(R.string.settings_provider_badges) {
                SwitchItem(R.string.settings_provider_badges, badges in settings.badgeProviders, { vm.setBadgeProvider(badges, it) }, R.string.settings_provider_badges_hint)
            }
        }
    }
    if (provider == Provider.SevenTv) SevenTvExtras(settings, vm)
    provider.badges?.takeIf { it in settings.badgeProviders }?.let { IndividualBadges(vm, it, searchable = provider == Provider.Twitch) }
}

/** Each only while what it belongs to is on: activity and unlisted emotes to the emotes, paints to the badges. */
@Composable
private fun SevenTvExtras(settings: Settings, vm: MainViewModel) {
    val emotes = EmoteProvider.SevenTv in settings.emoteProvidersOn
    val badges = BadgeProvider.SevenTv in settings.badgeProviders
    if (!emotes && !badges) return
    SettingsGroup(R.string.settings_group_seventv_more) {
        if (emotes) {
            item(R.string.settings_unlisted_7tv) { SwitchItem(R.string.settings_unlisted_7tv, settings.showUnlisted7tv, vm::setShowUnlisted7tv, R.string.settings_unlisted_7tv_hint) }
            item(R.string.settings_seventv_events) { SwitchItem(R.string.settings_seventv_events, settings.sevenTvEvents, vm::setSevenTvEvents, R.string.settings_seventv_events_hint) }
        }
        if (badges) {
            item(R.string.settings_seventv_paints) { SwitchItem(R.string.settings_seventv_paints, settings.sevenTvPaints, vm::setSevenTvPaints, R.string.settings_seventv_paints_hint) }
        }
    }
}

/**
 * The kinds of badge from [provider] Chatter has loaded, Twitch's whole global list included, each
 * with a switch. Without a search only hidden and well-known kinds are listed: all of them would be
 * hundreds of rows, and settings pages draw every row at once. Unless [searchable], only the hidden
 * ones show, so a badge hidden by holding it in chat can be shown again.
 */
@Composable
private fun IndividualBadges(vm: MainViewModel, provider: BadgeProvider, searchable: Boolean) {
    val hidden by vm.hiddenBadges.collectAsStateWithLifecycle()
    val hiddenKinds by vm.hiddenBadgeKinds.collectAsStateWithLifecycle()
    // Read once, and sorted only by what was hidden on opening: a switch must not move its row.
    val known = remember { vm.knownBadgeKinds() }
    val hiddenOnOpen = remember { hiddenKinds }
    var query by rememberSaveable { mutableStateOf("") }
    val rows = (hidden.map { BadgeKindRow(it.kind, it.provider, it.set, it.title, it.url, it.urlOnLight, it.background) } +
        known.map { BadgeKindRow(it.kind, it.provider, it.set, it.title, it.url, it.urlOnLight, it.background) })
        .filter { it.provider == provider && (searchable || it.kind in hiddenOnOpen) }
        .distinctBy { it.kind }
    if (!searchable && rows.isEmpty()) return
    val search = query.trim()
    val matches = rows.map { it to badgeKindName(it.provider, it.set, it.title) }
        .filter { (row, name) ->
            if (search.isEmpty()) row.kind in hiddenOnOpen || isWellKnownBadge(row.provider, row.set)
            else name.contains(search, ignoreCase = true) || row.title.contains(search, ignoreCase = true)
        }
        .sortedWith(compareBy({ it.first.kind !in hiddenOnOpen }, { it.second.lowercase() }))
    val named = matches.take(MAX_BADGE_ROWS)
    val more = (if (search.isEmpty()) rows.size else matches.size) - named.size
    SettingsGroup(R.string.settings_badge_kinds) {
        if (searchable) item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (rows.isEmpty()) {
                    Text(stringResource(R.string.settings_badge_kinds_empty))
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.settings_badge_kinds_search)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
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
                    leadingContent = {
                        AsyncImage(
                            model = badgePicture(row.url, row.urlOnLight),
                            contentDescription = null,
                            imageLoader = vm.imageLoader,
                            modifier = Modifier.size(24.dp).badgeBackground(row.background),
                        )
                    },
                    trailingContent = { SettingsSwitch(checked = shown, onCheckedChange = change) },
                    colors = transparentItem(),
                    modifier = Modifier.tileClickable { change(!shown) },
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
    val background: Int?,
) {
    fun toBadge() = Badge(url.orEmpty(), title, provider, set, urlOnLight, background)
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
                        val label = stringResource(R.string.settings_hidden_emotes_show, emote.name)
                        OutlinedButton(onClick = { vm.showEmote(emote) }, modifier = Modifier.semantics { contentDescription = label }) {
                            Text(stringResource(R.string.settings_hidden_emotes_show_short))
                        }
                    },
                    colors = transparentItem(),
                )
            }
        }
    }
}

/**
 * Where emotes and badges come from. The emote providers come first, in order of precedence.
 * [tint] colors a logo known by its color; null takes the text color, [Color.Unspecified] keeps the
 * colors it is drawn in.
 */
internal enum class Provider(val title: Int, val logo: Int, val tint: Color?, val emotes: EmoteProvider?, val badges: BadgeProvider?) {
    Twitch(R.string.settings_provider_twitch, R.drawable.ic_site_twitch, Color(0xFF9146FF), EmoteProvider.Twitch, BadgeProvider.Twitch),
    SevenTv(R.string.settings_provider_seventv, R.drawable.ic_provider_seventv, null, EmoteProvider.SevenTv, BadgeProvider.SevenTv),
    Bttv(R.string.settings_provider_bttv, R.drawable.ic_provider_bttv, Color(0xFFD50014), EmoteProvider.Bttv, null),
    Ffz(R.string.settings_provider_ffz, R.drawable.ic_provider_ffz, null, EmoteProvider.Ffz, BadgeProvider.Ffz),
    Chatterino(R.string.settings_provider_chatterino, R.drawable.ic_provider_chatterino, Color.Unspecified, null, BadgeProvider.Chatterino),
    Chatter(R.string.settings_provider_chatter, R.drawable.ic_notification, null, null, BadgeProvider.Chatter),
}

internal fun providerPage(provider: Provider): SettingsSubPage = when (provider) {
    Provider.Twitch -> SettingsSubPage.ProviderTwitch
    Provider.SevenTv -> SettingsSubPage.ProviderSevenTv
    Provider.Bttv -> SettingsSubPage.ProviderBttv
    Provider.Ffz -> SettingsSubPage.ProviderFfz
    Provider.Chatterino -> SettingsSubPage.ProviderChatterino
    Provider.Chatter -> SettingsSubPage.ProviderChatter
}
