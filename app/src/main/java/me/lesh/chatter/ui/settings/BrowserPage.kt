package me.lesh.chatter.ui.settings

import androidx.compose.runtime.Composable
import me.lesh.chatter.R
import me.lesh.chatter.settings.Settings
import me.lesh.chatter.ui.MainViewModel

/** Where tapped links open: over the chat in the user's browser, how large, and which go to apps. */
@Composable
internal fun BrowserPage(settings: Settings, vm: MainViewModel) {
    SettingsGroup {
        item(R.string.settings_links_in_app) {
            SwitchItem(R.string.settings_links_in_app, settings.linksInApp, vm::setLinksInApp, R.string.settings_links_in_app_hint)
        }
        if (settings.linksInApp) {
            item(R.string.settings_links_full_screen) {
                SwitchItem(
                    R.string.settings_links_full_screen, settings.linksFullScreen,
                    vm::setLinksFullScreen, R.string.settings_links_full_screen_hint,
                )
            }
            if (!settings.linksFullScreen) {
                item(R.string.settings_chat_above_links) {
                    SwitchItem(
                        R.string.settings_chat_above_links, settings.chatAboveLinks,
                        vm::setChatAboveLinks, R.string.settings_chat_above_links_hint,
                    )
                }
            }
            item(R.string.settings_posts_in_their_app) {
                SwitchItem(
                    R.string.settings_posts_in_their_app, settings.postsInTheirApp,
                    vm::setPostsInTheirApp, R.string.settings_posts_in_their_app_hint,
                )
            }
        }
    }
}
