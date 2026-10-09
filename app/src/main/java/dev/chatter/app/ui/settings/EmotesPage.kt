package dev.chatter.app.ui.settings

import androidx.compose.runtime.Composable
import dev.chatter.app.R
import dev.chatter.app.badges.BadgeProvider
import dev.chatter.app.emotes.EmoteProvider
import dev.chatter.app.settings.Settings
import dev.chatter.app.ui.MainViewModel

/** Emotes and badges in one place, providers included. */
@Composable
internal fun EmotesPage(settings: Settings, vm: MainViewModel) {
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
