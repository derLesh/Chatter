package me.lesh.chatter.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.SideEffect
import me.lesh.chatter.ui.settings.EnterInOrder
import me.lesh.chatter.ui.settings.LocalPageEntrance
import me.lesh.chatter.ui.settings.PageEntrance
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.Alignment
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import me.lesh.chatter.BuildConfig
import me.lesh.chatter.ui.chat.BuildStripe
import me.lesh.chatter.ui.chat.botName
import me.lesh.chatter.R
import me.lesh.chatter.auth.AuthState
import me.lesh.chatter.chat.Bot
import me.lesh.chatter.settings.Settings
import me.lesh.chatter.stats.Stats
import me.lesh.chatter.ui.changelog.ChangelogPage
import me.lesh.chatter.ui.channels.OfferUndoRemoval
import me.lesh.chatter.ui.settings.APP_ICON_TERMS
import me.lesh.chatter.ui.settings.AboutPage
import me.lesh.chatter.ui.settings.AccountPage
import me.lesh.chatter.ui.settings.AccountSummaryCard
import me.lesh.chatter.ui.settings.AppIconsPage
import me.lesh.chatter.ui.settings.AppearancePage
import me.lesh.chatter.ui.settings.BADGE_PROVIDERS
import me.lesh.chatter.ui.settings.BackupPage
import me.lesh.chatter.ui.settings.BetaPage
import me.lesh.chatter.ui.settings.BlockedUsersPage
import me.lesh.chatter.ui.settings.CategoryHue
import me.lesh.chatter.ui.settings.CategoryIcon
import me.lesh.chatter.ui.settings.ChannelsPage
import me.lesh.chatter.ui.settings.CreditsPage
import me.lesh.chatter.ui.settings.DebugStatsPage
import me.lesh.chatter.ui.settings.DeveloperPage
import me.lesh.chatter.ui.settings.EmotesPage
import me.lesh.chatter.ui.settings.HiddenEmotesPage
import me.lesh.chatter.ui.settings.BadgeKindsPage
import me.lesh.chatter.ui.settings.BotCommandsPage
import me.lesh.chatter.ui.settings.FiltersPage
import me.lesh.chatter.ui.settings.ImageHostsPage
import me.lesh.chatter.ui.settings.KeywordsPage
import me.lesh.chatter.ui.settings.LINE_SPACINGS
import me.lesh.chatter.ui.settings.LiveChannelsPage
import me.lesh.chatter.ui.settings.LocalSettingsTarget
import me.lesh.chatter.ui.settings.MediaPage
import me.lesh.chatter.ui.settings.MessagesPage
import me.lesh.chatter.ui.settings.MOBILE_DATA
import me.lesh.chatter.ui.settings.SEND_LATER_BUTTONS
import me.lesh.chatter.ui.settings.NAME_STYLES
import me.lesh.chatter.ui.settings.NotificationsPage
import me.lesh.chatter.ui.settings.PROVIDERS
import me.lesh.chatter.ui.settings.PostsPage
import me.lesh.chatter.ui.settings.SPECIAL_MESSAGE_TITLES
import me.lesh.chatter.ui.settings.SpecialMessagesPage
import me.lesh.chatter.ui.settings.RuleEditorPage
import me.lesh.chatter.ui.settings.RulesPage
import me.lesh.chatter.ui.settings.SettingsGroup
import me.lesh.chatter.ui.settings.SettingsSearch
import me.lesh.chatter.ui.settings.StatsPage
import me.lesh.chatter.ui.settings.StoragePage
import me.lesh.chatter.ui.settings.SupportPage
import me.lesh.chatter.ui.settings.THEME_MODES
import me.lesh.chatter.ui.settings.TypingPage
import me.lesh.chatter.ui.settings.tileClickable
import me.lesh.chatter.ui.settings.transparentItem
import me.lesh.chatter.ui.theme.isAppInDarkTheme
import me.lesh.chatter.ui.update.UpdateCard
import me.lesh.chatter.ui.update.UpdatePage
import kotlinx.coroutines.launch
import me.lesh.chatter.util.shownVersion

/** The headed groups the categories are listed in on the settings home. */
private enum class SettingsSection(val title: Int) {
    Chat(R.string.settings_chat),
    Channels(R.string.settings_section_channels),
    Data(R.string.settings_section_data),
    App(R.string.app_name),
}

/**
 * Top level of the settings: categories that open a page, like Android's settings app. A category
 * without a [section] is reached another way.
 */
private enum class SettingsPage(
    val title: Int,
    val summary: Int,
    val section: SettingsSection?,
    val icon: @Composable () -> ImageVector,
    val hue: CategoryHue? = null,
) {
    Appearance(R.string.settings_appearance, R.string.settings_appearance_summary, SettingsSection.Chat, { drawableIcon(R.drawable.ic_palette) }, CategoryHue.Pink),
    Messages(R.string.settings_messages, R.string.settings_messages_summary, SettingsSection.Chat, { drawableIcon(R.drawable.ic_chat) }, CategoryHue.Blue),
    Media(R.string.settings_media, R.string.settings_media_summary, SettingsSection.Chat, { drawableIcon(R.drawable.ic_image) }, CategoryHue.Green),
    Typing(R.string.settings_typing, R.string.settings_typing_summary, SettingsSection.Chat, { drawableIcon(R.drawable.ic_keyboard) }, CategoryHue.Orange),
    Emotes(R.string.settings_emotes, R.string.settings_emotes_summary, SettingsSection.Chat, { Icons.Default.Face }, CategoryHue.Amber),
    Filters(R.string.settings_filters, R.string.settings_filters_summary, SettingsSection.Chat, { drawableIcon(R.drawable.ic_filter_list) }, CategoryHue.Purple),
    Channels(R.string.settings_channels, R.string.settings_channels_summary, SettingsSection.Channels, { Icons.Default.Person }, CategoryHue.Teal),
    Notifications(R.string.settings_notifications, R.string.settings_notifications_summary, SettingsSection.Channels, { Icons.Default.Notifications }, CategoryHue.Red),
    Stats(R.string.settings_stats, R.string.settings_stats_summary, SettingsSection.Data, { drawableIcon(R.drawable.ic_bar_chart) }, CategoryHue.Indigo),
    Storage(R.string.settings_storage, R.string.settings_storage_summary, SettingsSection.Data, { drawableIcon(R.drawable.ic_storage) }, CategoryHue.Gray),
    Backup(R.string.settings_group_backup, R.string.settings_backup_summary, SettingsSection.Data, { drawableIcon(R.drawable.ic_backup) }, CategoryHue.Sky),
    /** Only in the build people install themselves; see SPONSOR_LINK in build.gradle.kts. */
    /** What is new and not finished; marked as beta wherever it shows. */
    Beta(R.string.settings_beta, R.string.settings_beta_summary, SettingsSection.App, { drawableIcon(R.drawable.ic_science) }, CategoryHue.Lime),
    Support(R.string.settings_support, R.string.settings_support_summary, SettingsSection.App, { Icons.Default.Favorite }, CategoryHue.Pink),
    About(R.string.settings_about, R.string.settings_about_summary, SettingsSection.App, { Icons.Default.Info }, CategoryHue.Gray),
    /** Only in debug builds. */
    Developer(R.string.settings_developer, R.string.settings_developer_summary, SettingsSection.App, { Icons.Default.Build }, CategoryHue.Gray),
    /** Opened from the profile card above the sections. */
    Account(R.string.settings_account, R.string.settings_account_summary, null, { Icons.Default.AccountCircle }),
    ;

    val shown: Boolean get() = when (this) {
        Support -> BuildConfig.SPONSOR_LINK
        Developer -> BuildConfig.DEBUG
        else -> true
    }
}

@Composable
private fun drawableIcon(id: Int) = ImageVector.vectorResource(id)

/** A page opened from within a category. */
internal enum class SettingsSubPage(val title: Int) {
    BlockedUsers(R.string.settings_blocked_users),
    MentionKeywords(R.string.settings_keywords),
    MuteKeywords(R.string.settings_mute_keywords),
    ImageHosts(R.string.settings_image_hosts),
    HiddenEmotes(R.string.settings_hidden_emotes),
    BadgeKinds(R.string.settings_badge_kinds),
    Posts(R.string.settings_posts),
    SpecialMessages(R.string.settings_special_messages),
    BotCommands(R.string.settings_bot_commands),
    AppIcons(R.string.settings_app_icon),
    LiveChannels(R.string.settings_live_channels),
    Rules(R.string.settings_rules),
    Changelog(R.string.settings_changelog),
    Update(R.string.update_page_title),
    Credits(R.string.settings_credits),
    DebugStats(R.string.debug_stats),
}

/**
 * One searchable setting: its title, its page, and other terms it may be found by. [key] is the
 * tile or group it opens at; null for a page itself.
 */
private class SearchEntry(
    val title: Int,
    val page: SettingsPage,
    val hint: Int? = null,
    val also: List<Int> = emptyList(),
    val key: Int? = title,
    val shownWhen: ShownWhen? = null,
)

/**
 * A setting that only shows while the setting at [key] is as [met] says; [met] also gets whether
 * the app is dark. While it is hidden, the search opens at that setting instead.
 */
private class ShownWhen(val key: Int, val met: (Settings, Boolean) -> Boolean)

private val unlessReadOnly = ShownWhen(R.string.settings_read_only) { s, _ -> !s.readOnly }

/**
 * Everything the search finds, in page order. A setting is found by its tile key
 * (`item(R.string.x)`), so a new searchable setting needs the key on its tile and a line here.
 */
private val SEARCH_INDEX: List<SearchEntry> by lazy {
    buildList {
        // The channel list has no tiles to open at, so its actions find the page.
        val channelActions = listOf(R.string.add_channel, R.string.rename_channel, R.string.combine_channels)
        SettingsPage.entries.filter { it.shown }.forEach { page ->
            val also = if (page == SettingsPage.Channels) channelActions else emptyList()
            add(SearchEntry(page.title, page, page.summary, also, key = null))
        }
        val appearance = SettingsPage.Appearance
        add(SearchEntry(R.string.settings_theme, appearance, also = THEME_MODES.map { it.second } + R.string.search_also_theme))
        add(SearchEntry(R.string.settings_dynamic_color, appearance, R.string.settings_dynamic_color_hint, also = listOf(R.string.search_also_dynamic_color)))
        add(SearchEntry(R.string.settings_pure_black, appearance, R.string.settings_pure_black_hint, shownWhen = ShownWhen(R.string.settings_theme) { _, dark -> dark }))
        add(SearchEntry(R.string.settings_highlight_color, appearance, R.string.settings_highlight_color_hint, also = listOf(R.string.search_also_highlight_color)))
        add(SearchEntry(R.string.settings_name_colors, appearance, R.string.settings_name_colors_hint))
        add(SearchEntry(R.string.settings_app_icon, appearance, also = APP_ICON_TERMS))
        add(SearchEntry(R.string.settings_font_size, appearance, also = listOf(R.string.search_also_font_size)))
        add(SearchEntry(R.string.settings_line_spacing, appearance, also = LINE_SPACINGS.map { it.second }))
        add(SearchEntry(R.string.settings_emote_size, appearance))
        add(SearchEntry(R.string.settings_message_dividers, appearance, R.string.settings_message_dividers_hint))
        add(SearchEntry(R.string.settings_alternate_background, appearance, R.string.settings_alternate_background_hint))
        add(SearchEntry(R.string.settings_timestamps, appearance))
        add(SearchEntry(R.string.settings_name_style, appearance, also = NAME_STYLES.map { it.second }))
        val messages = SettingsPage.Messages
        add(SearchEntry(R.string.settings_smooth_scrolling, messages, R.string.settings_smooth_scrolling_hint))
        add(SearchEntry(R.string.settings_show_deleted, messages, R.string.settings_show_deleted_hint))
        add(SearchEntry(R.string.settings_special_messages, messages, also = SPECIAL_MESSAGE_TITLES.map { it.second }))
        add(SearchEntry(R.string.settings_message_limit, messages))
        add(SearchEntry(R.string.settings_load_history, messages, R.string.settings_load_history_hint))
        add(SearchEntry(R.string.settings_message_tap, messages))
        add(SearchEntry(R.string.settings_name_tap, messages))
        // Their titles leave out the user card, which only the group's title names.
        val userCard = listOf(R.string.settings_group_user_card)
        add(SearchEntry(R.string.settings_user_card_buttons_first, messages, R.string.settings_user_card_buttons_first_hint, userCard))
        add(SearchEntry(R.string.settings_copy_first, messages, also = userCard))
        add(SearchEntry(R.string.settings_user_card_attachments, messages, R.string.settings_user_card_attachments_hint, userCard))
        add(SearchEntry(R.string.settings_keep_screen_on, messages, R.string.settings_keep_screen_on_hint))
        add(SearchEntry(R.string.settings_keep_screen_on_when_quiet, messages, R.string.settings_keep_screen_on_when_quiet_hint, shownWhen = ShownWhen(R.string.settings_keep_screen_on) { s, _ -> s.keepScreenOn }))
        val media = SettingsPage.Media
        add(SearchEntry(R.string.settings_inline_images, media, R.string.settings_inline_images_hint))
        add(SearchEntry(R.string.settings_image_hosts, media))
        add(SearchEntry(R.string.settings_show_gifs, media, R.string.settings_show_gifs_hint))
        add(
            SearchEntry(
                R.string.settings_posts, media,
                also = listOf(
                    R.string.post_site_x, R.string.post_site_bluesky, R.string.settings_post_avatar, R.string.settings_post_media,
                    R.string.settings_post_counts, R.string.settings_post_picture_size, R.string.settings_blur_nsfw_posts,
                    R.string.settings_post_date, R.string.settings_post_text,
                ),
            ),
        )
        add(SearchEntry(R.string.settings_full_links, media, R.string.settings_full_links_hint))
        val typing = SettingsPage.Typing
        add(SearchEntry(R.string.settings_read_only, typing, R.string.settings_read_only_hint))
        add(SearchEntry(R.string.settings_emote_suggestions, typing, R.string.settings_emote_suggestions_hint, shownWhen = unlessReadOnly))
        add(SearchEntry(R.string.settings_user_suggestions, typing, R.string.settings_user_suggestions_hint, shownWhen = unlessReadOnly))
        add(SearchEntry(R.string.settings_bot_commands, typing, R.string.settings_bot_commands_hint, also = Bot.entries.map(::botName), shownWhen = unlessReadOnly))
        add(SearchEntry(R.string.settings_mention_with_at, typing, R.string.settings_mention_with_at_hint, shownWhen = unlessReadOnly))
        add(SearchEntry(R.string.settings_suggestions_over_chat, typing, R.string.settings_suggestions_over_chat_hint, shownWhen = unlessReadOnly))
        add(SearchEntry(R.string.settings_recently_sent, typing, R.string.settings_recently_sent_hint, shownWhen = unlessReadOnly))
        add(SearchEntry(R.string.settings_chat_modes_button, typing, R.string.settings_chat_modes_button_hint, shownWhen = unlessReadOnly))
        add(SearchEntry(R.string.settings_reply_card, typing, R.string.settings_reply_card_hint, shownWhen = unlessReadOnly))
        if (BuildStripe.offered) add(SearchEntry(R.string.settings_build_stripe, typing, R.string.settings_build_stripe_hint, shownWhen = unlessReadOnly))
        val emotes = SettingsPage.Emotes
        add(SearchEntry(R.string.settings_emotes_enabled, emotes, R.string.settings_emotes_new_messages_hint))
        add(SearchEntry(R.string.settings_animated_emotes, emotes))
        add(SearchEntry(R.string.settings_slow_idle_emotes, emotes, R.string.settings_slow_idle_emotes_hint, shownWhen = ShownWhen(R.string.settings_animated_emotes) { s, _ -> s.animatedEmotes }))
        add(SearchEntry(R.string.settings_zero_width, emotes, R.string.settings_zero_width_hint))
        add(SearchEntry(R.string.settings_unlisted_7tv, emotes, R.string.settings_unlisted_7tv_hint))
        add(SearchEntry(R.string.settings_seventv_events, emotes, R.string.settings_seventv_events_hint))
        add(SearchEntry(R.string.settings_seventv_paints, emotes, R.string.settings_seventv_paints_hint))
        add(SearchEntry(R.string.settings_hidden_emotes, emotes, R.string.settings_hidden_emotes_hint))
        add(SearchEntry(R.string.settings_emote_providers, emotes, also = PROVIDERS.map { it.second }))
        add(SearchEntry(R.string.settings_badge_providers, emotes, also = BADGE_PROVIDERS.map { it.second }))
        add(SearchEntry(R.string.settings_badge_kinds, emotes, R.string.settings_badge_kinds_hint))
        val filters = SettingsPage.Filters
        add(SearchEntry(R.string.settings_mute_keywords, filters, R.string.settings_mute_keywords_summary, also = listOf(R.string.search_also_mute_keywords)))
        add(SearchEntry(R.string.settings_rules, filters, R.string.settings_rules_summary, also = listOf(R.string.rule_regex, R.string.search_also_rules)))
        add(SearchEntry(R.string.settings_blocked_users, filters))
        val notifications = SettingsPage.Notifications
        add(SearchEntry(R.string.settings_keywords, notifications, R.string.settings_keywords_summary))
        add(SearchEntry(R.string.settings_sender_avatars, notifications, R.string.settings_sender_avatars_hint))
        add(SearchEntry(R.string.settings_haptics, notifications, R.string.settings_haptics_hint))
        add(SearchEntry(R.string.settings_system_notifications, notifications, R.string.settings_notifications_hint))
        add(SearchEntry(R.string.settings_bubbles, notifications, R.string.settings_bubbles_hint))
        add(SearchEntry(R.string.settings_live_notifications, notifications, R.string.settings_live_notifications_hint))
        add(SearchEntry(R.string.settings_live_channels, notifications, R.string.settings_live_channels_summary, shownWhen = ShownWhen(R.string.settings_live_notifications) { s, _ -> s.liveNotifications }))
        val channels = SettingsPage.Channels
        add(SearchEntry(R.string.settings_channel_tabs, channels, R.string.settings_channel_tabs_hint))
        add(SearchEntry(R.string.settings_unread_title_bar, channels, R.string.settings_unread_title_bar_hint))
        add(SearchEntry(R.string.settings_carousel, channels, R.string.settings_carousel_hint))
        add(SearchEntry(R.string.settings_stats_reset, SettingsPage.Stats, R.string.settings_stats_reset_hint))
        val storage = SettingsPage.Storage
        add(SearchEntry(R.string.settings_stats_group_data, storage, also = listOf(R.string.settings_stats_data_background)))
        add(SearchEntry(R.string.settings_mobile_data, storage, R.string.settings_mobile_data_hint, also = MOBILE_DATA.map { it.second }))
        add(SearchEntry(R.string.storage_group, storage, also = listOf(R.string.storage_images, R.string.storage_emote_lists)))
        add(SearchEntry(R.string.storage_clear_cache, storage, R.string.storage_clear_cache_hint))
        val backup = SettingsPage.Backup
        add(SearchEntry(R.string.backup_export, backup, R.string.backup_export_hint))
        add(SearchEntry(R.string.backup_import, backup, R.string.backup_import_hint))
        val account = SettingsPage.Account
        add(SearchEntry(R.string.settings_group_accounts, account, also = listOf(R.string.logout, R.string.account_add_short)))
        add(SearchEntry(R.string.settings_catch_up, SettingsPage.Beta, R.string.settings_catch_up_hint))
        add(SearchEntry(R.string.settings_colorful_categories, SettingsPage.Beta, R.string.settings_colorful_categories_hint))
        add(SearchEntry(R.string.settings_send_later, SettingsPage.Beta, R.string.settings_send_later_hint))
        add(SearchEntry(R.string.settings_send_later_button, SettingsPage.Beta, also = SEND_LATER_BUTTONS.map { it.second }))
        if (BuildConfig.ASSISTANT_ACCESS) add(SearchEntry(R.string.settings_assistant, SettingsPage.Beta, R.string.settings_assistant_hint))
        val about = SettingsPage.About
        add(SearchEntry(R.string.settings_changelog, about, R.string.settings_changelog_summary))
        add(SearchEntry(R.string.settings_source_code, about, R.string.settings_source_code_summary))
        add(SearchEntry(R.string.settings_report_issue, about, R.string.settings_report_issue_summary))
        add(SearchEntry(R.string.settings_security, about, R.string.settings_security_summary))
        add(SearchEntry(R.string.settings_privacy, about, R.string.settings_privacy_summary))
        add(SearchEntry(R.string.settings_credits, about, R.string.settings_credits_summary))
        // Only the GitHub APK has this switch.
        if (BuildConfig.UPDATE_CHECK) add(SearchEntry(R.string.settings_update_check, about, R.string.settings_update_check_hint))
        if (BuildConfig.DEBUG) {
            add(SearchEntry(R.string.settings_show_onboarding, SettingsPage.Developer, R.string.settings_show_onboarding_hint))
            add(SearchEntry(R.string.debug_stats, SettingsPage.Developer, R.string.debug_stats_hint))
        }
    }
}

private data class SearchResult(val entry: SearchEntry, val title: String, val subtitle: String)

/**
 * Twitch's login page over the settings. [signedOut] starts without Twitch's session, needed for
 * adding an account but not for logging the active one in again.
 */
private data class TwitchLogin(val url: String, val signedOut: Boolean)

/** [SettingsPlace.rule] while a rule is being written that is not saved yet. */
private const val NEW_RULE = ""

/** The current position in the settings: search, category, sub-page, rule editor. */
private data class SettingsPlace(
    val page: SettingsPage? = null,
    val subPage: SettingsSubPage? = null,
    /** The search is open; a category opened from it returns to it. */
    val searching: Boolean = false,
    /** The id of the rule in the editor, or [NEW_RULE]. */
    val rule: String? = null,
) {
    val isHomeOrSearch: Boolean get() = page == null && subPage == null && rule == null

    /** Decides whether a transition goes forward or back. */
    val depth: Int get() = listOf(searching, page != null, subPage != null, rule != null).count { it }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var subPage by rememberSaveable { mutableStateOf<SettingsSubPage?>(null) }
    var rule by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // The setting a search result opened, highlighted until the page is left.
    var target by rememberSaveable { mutableStateOf<Int?>(null) }
    // Twitch's login page over the settings, while adding an account or logging in again.
    var addAccount by remember { mutableStateOf<TwitchLogin?>(null) }
    var addFailed by remember { mutableStateOf<String?>(null) }
    var addWithToken by rememberSaveable { mutableStateOf(false) }
    // A login from the website takes over from the token screen.
    val linkedLogin by vm.linkedLogin.collectAsStateWithLifecycle()
    LaunchedEffect(linkedLogin != null) { if (linkedLogin != null) addWithToken = false }
    val goBack = {
        when {
            addWithToken -> addWithToken = false
            addAccount != null -> addAccount = null
            rule != null -> rule = null
            subPage != null -> subPage = null
            page != null -> {
                page = null
                target = null
            }
            searching -> searching = false
            else -> onBack()
        }
    }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val dark = isAppInDarkTheme()
    val auth by vm.authState.collectAsStateWithLifecycle()
    val update by vm.availableUpdate.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // The card on top shows what is stored right away; this only updates it now and then.
    LaunchedEffect(Unit) { vm.refreshAccounts() }
    // The channel page can remove channels and offers undo like the chat does.
    val snackbar = remember { SnackbarHostState() }
    OfferUndoRemoval(vm, snackbar)

    addFailed?.let { error ->
        AlertDialog(
            onDismissRequest = { addFailed = null },
            title = { Text(stringResource(R.string.account_add)) },
            text = { Text(stringResource(R.string.login_failed, error)) },
            confirmButton = { TextButton(onClick = { addFailed = null }) { Text(stringResource(R.string.ok)) } },
        )
    }

    addAccount?.let { login ->
        NavigationBackHandler(
            state = rememberNavigationEventState(currentInfo = NavigationEventInfo.None),
            onBackCompleted = { addAccount = null },
        )
        LoginWebView(login.url, Modifier.fillMaxSize().safeDrawingPadding(), signedOut = login.signedOut) { redirect ->
            scope.launch {
                val result = vm.handleRedirect(redirect) ?: return@launch
                addAccount = null
                addFailed = result.exceptionOrNull()?.let { it.message ?: it.toString() }
            }
        }
        return
    }

    if (addWithToken) {
        TokenLoginScreen(vm, onBack = { addWithToken = false }, onLoggedIn = { addWithToken = false })
        return
    }

    val place = SettingsPlace(page, subPage, searching, rule)
    // One step up. From the category list, leaving the settings is animated by the parent screen.
    val backTo = when {
        rule != null -> place.copy(rule = null)
        subPage != null -> place.copy(subPage = null)
        page != null -> place.copy(page = null)
        searching -> place.copy(searching = false)
        else -> null
    }
    // The home comes in group by group as the settings open; later it is simply there.
    val opening = remember { booleanArrayOf(true) }
    SideEffect { opening[0] = false }
    SharedTransitionLayout {
    rememberPredictiveTransition(place, backTo, onBack = goBack, label = "settings-page").AnimatedContent(
        transitionSpec = {
            // The search opens in place: its field grows into the bar while the rest fades.
            if (targetState.isHomeOrSearch && initialState.isHomeOrSearch) {
                fadeIn(tween(durationMillis = 220, delayMillis = 60)) togetherWith fadeOut(tween(durationMillis = 120))
            } else {
                slideBetweenScreens(forward = targetState.depth > initialState.depth)
            }
        },
    ) { (current, currentSub, currentSearching, currentRule) ->
        val searchBar = Modifier.sharedBounds(
            rememberSharedContentState(SEARCH_BAR),
            animatedVisibilityScope = this,
            // The old label is gone before the new one shows, so the two never sit on each other.
            enter = fadeIn(tween(durationMillis = 120, delayMillis = 80)),
            exit = fadeOut(tween(durationMillis = 60)),
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = OverlayClip(CircleShape),
        )
        if (currentRule != null) {
            // Looked up once; the editor keeps its own copy from then on.
            val editing = remember(currentRule) { vm.rules.value.find { it.id == currentRule } }
            RuleEditorPage(
                rule = editing,
                savedRules = vm.rules.collectAsStateWithLifecycle().value,
                loadedMessages = vm::messagesToTryRulesOn,
                onSave = {
                    vm.saveRule(it)
                    goBack()
                },
                onBack = goBack,
            )
            return@AnimatedContent
        }
        if (current == null && currentSearching) {
            SearchPage(
                modifier = searchBar,
                colorful = settings.colorfulCategories,
                query = query,
                onQuery = { query = it },
                onOpen = { entry ->
                    page = entry.page
                    target = entry.shownWhen?.takeUnless { it.met(settings, dark) }?.key ?: entry.key
                },
                onBack = goBack,
            )
            return@AnimatedContent
        }
        // The about page shows its own icon and name, so no title bar heading.
        val title = when {
            currentSub != null -> currentSub.title
            current == SettingsPage.About -> null
            else -> current?.title ?: R.string.settings
        }
        // The search field scrolls away with the home; the collapsed bar then offers it as an icon.
        val onSearch = if (current == null && currentSub == null) ({ searching = true }) else null
        SettingsPageScaffold(title = title, onBack = goBack, onSearch = onSearch, snackbar = snackbar, enterInOrder = opening[0] && current == null && currentSub == null) {
          CompositionLocalProvider(LocalSettingsTarget provides target.takeIf { currentSub == null }) {
            when (currentSub) {
                SettingsSubPage.BlockedUsers -> BlockedUsersPage(vm)
                SettingsSubPage.MentionKeywords -> KeywordsPage(
                    words = settings.mentionKeywords,
                    emptyText = R.string.keywords_none,
                    hint = R.string.settings_keywords_hint,
                    addTitle = R.string.keyword_add_mention,
                    onAdd = vm::addMentionKeyword,
                    onRemove = vm::removeMentionKeyword,
                )
                SettingsSubPage.MuteKeywords -> KeywordsPage(
                    words = settings.muteKeywords,
                    emptyText = R.string.mute_keywords_none,
                    hint = R.string.settings_mute_keywords_hint,
                    addTitle = R.string.keyword_add_mute,
                    onAdd = vm::addMuteKeyword,
                    onRemove = vm::removeMuteKeyword,
                )
                SettingsSubPage.ImageHosts -> ImageHostsPage(settings.imageHosts, vm)
                SettingsSubPage.HiddenEmotes -> HiddenEmotesPage(vm)
                SettingsSubPage.BadgeKinds -> BadgeKindsPage(vm)
                SettingsSubPage.Posts -> PostsPage(settings, vm)
                SettingsSubPage.SpecialMessages -> SpecialMessagesPage(settings, vm)
                SettingsSubPage.BotCommands -> BotCommandsPage(settings, vm)
                SettingsSubPage.AppIcons -> AppIconsPage(settings, vm)
                SettingsSubPage.LiveChannels -> LiveChannelsPage(vm)
                SettingsSubPage.Rules -> RulesPage(vm) { rule = it?.id ?: NEW_RULE }
                SettingsSubPage.Credits -> CreditsPage()
                SettingsSubPage.DebugStats -> DebugStatsPage(vm)
                SettingsSubPage.Changelog -> {
                    val releases by vm.releases.collectAsStateWithLifecycle()
                    ChangelogPage(releases, BuildConfig.VERSION_NAME)
                }
                // Gone once the new version is installed.
                SettingsSubPage.Update -> update?.let { UpdatePage(it) }
                null -> when (current) {
                    null -> {
                        EnterInOrder { SearchField(searchBar) { searching = true } }
                        EnterInOrder { AccountSummaryCard(auth, vm.imageLoader) { page = SettingsPage.Account } }
                        update?.let { EnterInOrder { UpdateCard(it, shownVersion) { subPage = SettingsSubPage.Update } } }
                        Home(settings.colorfulCategories) { page = it }
                    }
                    SettingsPage.Appearance -> AppearancePage(settings, vm) { subPage = it }
                    SettingsPage.Messages -> MessagesPage(settings, vm) { subPage = it }
                    SettingsPage.Media -> MediaPage(settings, vm) { subPage = it }
                    SettingsPage.Typing -> TypingPage(settings, vm) { subPage = it }
                    SettingsPage.Emotes -> EmotesPage(settings, vm) { subPage = it }
                    SettingsPage.Filters -> FiltersPage(settings, vm) { subPage = it }
                    SettingsPage.Notifications -> NotificationsPage(settings, vm) { subPage = it }
                    SettingsPage.Channels -> ChannelsPage(vm, settings)
                    SettingsPage.Stats -> StatsPage(vm) { page = SettingsPage.Channels }
                    SettingsPage.Storage -> StoragePage(settings, vm)
                    SettingsPage.Backup -> BackupPage(vm)
                    SettingsPage.Account -> AccountPage(
                        vm,
                        onAddAccount = { addAccount = TwitchLogin(vm.addAccountUrl(), signedOut = true) },
                        onAddAccountWithToken = { addWithToken = true },
                        // Same account: Twitch's session can stay, it only asks to grant the rest.
                        onReauthorize = { addAccount = TwitchLogin(vm.reauthorizeUrl(), signedOut = false) },
                    )
                    SettingsPage.Beta -> BetaPage(settings, vm)
                    SettingsPage.Support -> SupportPage(vm)
                    SettingsPage.About -> AboutPage(settings, vm) { subPage = it }
                    SettingsPage.Developer -> DeveloperPage(vm) { subPage = it }
                }
            }
          }
        }
    }
    }
}

/** Shared between the search field on the home and the bar of the search page. */
private const val SEARCH_BAR = "settings-search-bar"

/**
 * Search over all settings: a field in the title bar and the matches below, each with its category.
 * A result opens its category at the setting. [modifier] goes on the search bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchPage(modifier: Modifier, colorful: Boolean, query: String, onQuery: (String) -> Unit, onOpen: (SearchEntry) -> Unit, onBack: () -> Unit) {
    val resources = LocalResources.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val results = remember(query, resources) {
        fun text(res: Int) = SettingsSearch.plainTitle(resources.getString(res))
        val matching = SEARCH_INDEX.filter { e ->
            SettingsSearch.matches(query, (listOfNotNull(e.title, e.hint, e.page.title) + e.also).map(::text))
        }
        // Pages first, as the place to look around; then titles matching the query, then entries
        // that only mention it.
        val (pages, settings) = matching.partition { it.key == null }
        val (named, mentioned) = settings.partition { SettingsSearch.matches(query, listOf(text(it.title))) }
        // The subtitle says why a result matched: a page's summary, a setting's page, and for a
        // setting found by another word, that word too.
        fun why(e: SearchEntry): String {
            if (e.key == null) {
                val word = e.also.map(::text).firstOrNull { SettingsSearch.matches(query, listOf(it)) }
                return listOfNotNull(text(e.page.summary), word).joinToString(" · ")
            }
            val page = text(e.page.title)
            if (e in named) return page
            val reason = (listOfNotNull(e.hint) + e.also).map(::text).firstOrNull { SettingsSearch.matches(query, listOf(it)) }
            return if (reason == null) page else "$page · $reason"
        }
        (pages + named + mentioned).map { SearchResult(it, text(it.title), why(it)) }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        // With the keyboard, so the last results can still be scrolled up.
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            // The same pill as the field on the home, moved to the top, so opening the search
            // reads as that field growing rather than a new page.
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .then(modifier)
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    TextField(
                        value = query,
                        onValueChange = onQuery,
                        placeholder = { Text(stringResource(R.string.settings_search)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                    )
                    if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Default.Close, stringResource(R.string.settings_search_clear))
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (query.isNotBlank() && results.isEmpty()) {
                Text(
                    stringResource(R.string.settings_search_none),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
            if (results.isNotEmpty()) SettingsGroup {
                results.forEach { (entry, title, subtitle) ->
                    item {
                        ListItem(
                            headlineContent = { Text(highlighted(title, query)) },
                            supportingContent = { Text(highlighted(subtitle, query), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { CategoryIcon(entry.page.icon(), entry.page.hue.takeIf { colorful }) },
                            colors = transparentItem(),
                            modifier = Modifier.tileClickable { onOpen(entry) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** [text] with the words of [query] in bold, to show why a result matched. */
@Composable
private fun highlighted(text: String, query: String): AnnotatedString {
    val bold = SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    return buildAnnotatedString {
        append(text)
        query.split(' ').filter { it.isNotBlank() }.forEach { word ->
            var from = text.indexOf(word, ignoreCase = true)
            while (from >= 0) {
                addStyle(bold, from, from + word.length)
                from = text.indexOf(word, from + word.length, ignoreCase = true)
            }
        }
    }
}

/** One settings page with a collapsing large title bar and scrolling content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPageScaffold(
    title: Int?,
    onBack: () -> Unit,
    onSearch: (() -> Unit)? = null,
    snackbar: SnackbarHostState? = null,
    enterInOrder: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val back = @Composable {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
    }
    Scaffold(
        // Only the large bar collapses. On a page without one (about) the connection would swallow
        // every scroll.
        modifier = if (title != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        // With the keyboard, so a page's text field (the badge search) is not covered by it.
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
        topBar = {
            // Without a heading the large bar would be empty space, so a plain bar is used.
            if (title == null) {
                TopAppBar(title = {}, navigationIcon = back)
            } else {
                LargeTopAppBar(
                    title = { Text(stringResource(title)) },
                    navigationIcon = back,
                    actions = {
                        if (onSearch != null) {
                            AnimatedVisibility(scrollBehavior.state.collapsedFraction > 0.9f, enter = fadeIn(), exit = fadeOut()) {
                                IconButton(onClick = onSearch) { Icon(Icons.Default.Search, stringResource(R.string.settings_search)) }
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            CompositionLocalProvider(LocalPageEntrance provides remember { PageEntrance(enterInOrder) }) { content() }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Opens the search; a field rather than an icon, so it is the first thing seen on the home. */
@Composable
private fun SearchField(modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().height(56.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp)) {
            Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Text(
                stringResource(R.string.settings_search),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The categories, in their sections. */
@Composable
private fun Home(colorful: Boolean, open: (SettingsPage) -> Unit) {
    val pages = SettingsPage.entries.filter { it.shown }
    SettingsSection.entries.forEach { section ->
        SettingsGroup(section.title) {
            pages.filter { it.section == section }.forEach { p ->
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(p.title), fontWeight = FontWeight.Medium) },
                        supportingContent = { Text(stringResource(p.summary)) },
                        leadingContent = { CategoryIcon(p.icon(), p.hue.takeIf { colorful }) },
                        colors = transparentItem(),
                        modifier = Modifier.tileClickable { open(p) },
                    )
                }
            }
        }
    }
}
