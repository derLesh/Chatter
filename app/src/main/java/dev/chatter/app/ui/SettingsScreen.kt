package dev.chatter.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import coil3.ImageLoader
import dev.chatter.app.BuildConfig
import dev.chatter.app.R
import dev.chatter.app.auth.Account
import dev.chatter.app.auth.AuthState
import dev.chatter.app.stats.Stats
import dev.chatter.app.ui.changelog.ChangelogPage
import dev.chatter.app.ui.channels.OfferUndoRemoval
import dev.chatter.app.ui.settings.AboutPage
import dev.chatter.app.ui.settings.AccountPage
import dev.chatter.app.ui.settings.AccountRowIcon
import dev.chatter.app.ui.settings.AppearancePage
import dev.chatter.app.ui.settings.BADGE_PROVIDERS
import dev.chatter.app.ui.settings.BackupGroup
import dev.chatter.app.ui.settings.BlockedUsersPage
import dev.chatter.app.ui.settings.CategoryIcon
import dev.chatter.app.ui.settings.ChannelsPage
import dev.chatter.app.ui.settings.ChatPage
import dev.chatter.app.ui.settings.CreditsPage
import dev.chatter.app.ui.settings.EmotesPage
import dev.chatter.app.ui.settings.FiltersPage
import dev.chatter.app.ui.settings.ImageHostsPage
import dev.chatter.app.ui.settings.KeywordsPage
import dev.chatter.app.ui.settings.LiveChannelsPage
import dev.chatter.app.ui.settings.LocalSettingsTarget
import dev.chatter.app.ui.settings.MOBILE_DATA
import dev.chatter.app.ui.settings.NotificationsPage
import dev.chatter.app.ui.settings.PROVIDERS
import dev.chatter.app.ui.settings.RulesPage
import dev.chatter.app.ui.settings.SettingsGroup
import dev.chatter.app.ui.settings.SettingsSearch
import dev.chatter.app.ui.settings.StatsPage
import dev.chatter.app.ui.settings.SupportPage
import dev.chatter.app.ui.settings.THEME_MODES
import dev.chatter.app.ui.settings.transparentItem
import dev.chatter.app.ui.update.UpdateCard
import dev.chatter.app.ui.update.UpdatePage
import kotlinx.coroutines.launch

/** Top level of the settings: categories that open a page, like Android's settings app. */
private enum class SettingsPage(val title: Int, val summary: Int, val icon: ImageVector) {
    Appearance(R.string.settings_appearance, R.string.settings_appearance_summary, Icons.Default.Edit),
    Chat(R.string.settings_chat, R.string.settings_chat_summary, Icons.AutoMirrored.Filled.List),
    Emotes(R.string.settings_emotes, R.string.settings_emotes_summary, Icons.Default.Face),
    Filters(R.string.settings_filters, R.string.settings_filters_summary, Icons.Default.Lock),
    Notifications(R.string.settings_notifications, R.string.settings_notifications_summary, Icons.Default.Notifications),
    Channels(R.string.settings_channels, R.string.settings_channels_summary, Icons.Default.Person),
    Stats(R.string.settings_stats, R.string.settings_stats_summary, Icons.Default.DateRange),
    Account(R.string.settings_account, R.string.settings_account_summary, Icons.Default.AccountCircle),
    /** Only in the build people install themselves; see SPONSOR_LINK in build.gradle.kts. */
    Support(R.string.settings_support, R.string.settings_support_summary, Icons.Default.Favorite),
    About(R.string.settings_about, R.string.settings_about_summary, Icons.Default.Info),
    ;

    val shown: Boolean get() = this != Support || BuildConfig.SPONSOR_LINK
}

/** A page opened from within a category. */
internal enum class SettingsSubPage(val title: Int) {
    BlockedUsers(R.string.settings_blocked_users),
    MentionKeywords(R.string.settings_keywords),
    MuteKeywords(R.string.settings_mute_keywords),
    ImageHosts(R.string.settings_image_hosts),
    LiveChannels(R.string.settings_live_channels),
    Rules(R.string.settings_rules),
    Changelog(R.string.settings_changelog),
    Update(R.string.update_page_title),
    Credits(R.string.settings_credits),
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
)

/**
 * Everything the search finds, in page order. A setting is found by its tile key
 * (`item(R.string.x)`), so a new searchable setting needs the key on its tile and a line here.
 */
private val SEARCH_INDEX: List<SearchEntry> by lazy {
    buildList {
        SettingsPage.entries.filter { it.shown }.forEach { add(SearchEntry(it.title, it, it.summary, key = null)) }
        val appearance = SettingsPage.Appearance
        add(SearchEntry(R.string.settings_theme, appearance, also = THEME_MODES.map { it.second }))
        add(SearchEntry(R.string.settings_dynamic_color, appearance, R.string.settings_dynamic_color_hint))
        add(SearchEntry(R.string.settings_pure_black, appearance, R.string.settings_pure_black_hint))
        add(SearchEntry(R.string.settings_highlight_color, appearance, R.string.settings_highlight_color_hint))
        add(SearchEntry(R.string.settings_name_colors, appearance, R.string.settings_name_colors_hint))
        add(SearchEntry(R.string.settings_app_icon, appearance))
        add(SearchEntry(R.string.settings_font_size, appearance))
        add(SearchEntry(R.string.settings_timestamps, appearance))
        add(SearchEntry(R.string.settings_alternate_background, appearance, R.string.settings_alternate_background_hint))
        add(SearchEntry(R.string.settings_smooth_scrolling, appearance, R.string.settings_smooth_scrolling_hint))
        add(SearchEntry(R.string.settings_show_deleted, appearance, R.string.settings_show_deleted_hint))
        add(SearchEntry(R.string.settings_first_messages, appearance, R.string.settings_first_messages_hint))
        add(SearchEntry(R.string.settings_keep_screen_on, appearance, R.string.settings_keep_screen_on_hint))
        add(SearchEntry(R.string.settings_haptics, appearance, R.string.settings_haptics_hint))
        val chat = SettingsPage.Chat
        add(SearchEntry(R.string.settings_message_limit, chat))
        add(SearchEntry(R.string.settings_load_history, chat, R.string.settings_load_history_hint))
        add(SearchEntry(R.string.settings_inline_images, chat, R.string.settings_inline_images_hint))
        add(SearchEntry(R.string.settings_image_hosts, chat))
        add(SearchEntry(R.string.settings_full_links, chat, R.string.settings_full_links_hint))
        add(SearchEntry(R.string.settings_mobile_data, chat, R.string.settings_mobile_data_hint, also = MOBILE_DATA.map { it.second }))
        add(SearchEntry(R.string.settings_emote_suggestions, chat, R.string.settings_emote_suggestions_hint))
        add(SearchEntry(R.string.settings_user_suggestions, chat, R.string.settings_user_suggestions_hint))
        add(SearchEntry(R.string.settings_mention_with_at, chat, R.string.settings_mention_with_at_hint))
        add(SearchEntry(R.string.settings_message_tap, chat))
        add(SearchEntry(R.string.settings_name_tap, chat))
        add(SearchEntry(R.string.settings_carousel, chat, R.string.settings_carousel_hint))
        add(SearchEntry(R.string.settings_copy_first, chat, R.string.settings_copy_first_hint))
        val emotes = SettingsPage.Emotes
        add(SearchEntry(R.string.settings_emotes_enabled, emotes, R.string.settings_emotes_new_messages_hint))
        add(SearchEntry(R.string.settings_animated_emotes, emotes))
        add(SearchEntry(R.string.settings_slow_idle_emotes, emotes, R.string.settings_slow_idle_emotes_hint))
        add(SearchEntry(R.string.settings_zero_width, emotes, R.string.settings_zero_width_hint))
        add(SearchEntry(R.string.settings_unlisted_7tv, emotes, R.string.settings_unlisted_7tv_hint))
        add(SearchEntry(R.string.settings_seventv_events, emotes, R.string.settings_seventv_events_hint))
        add(SearchEntry(R.string.settings_seventv_paints, emotes, R.string.settings_seventv_paints_hint))
        add(SearchEntry(R.string.settings_emote_providers, emotes, also = PROVIDERS.map { it.second }))
        add(SearchEntry(R.string.settings_badge_providers, emotes, also = BADGE_PROVIDERS.map { it.second }))
        val filters = SettingsPage.Filters
        add(SearchEntry(R.string.settings_mute_keywords, filters, R.string.settings_mute_keywords_summary))
        add(SearchEntry(R.string.settings_rules, filters, R.string.settings_rules_summary))
        add(SearchEntry(R.string.settings_blocked_users, filters))
        val notifications = SettingsPage.Notifications
        add(SearchEntry(R.string.settings_keywords, notifications, R.string.settings_keywords_summary))
        add(SearchEntry(R.string.settings_sender_avatars, notifications, R.string.settings_sender_avatars_hint))
        add(SearchEntry(R.string.settings_system_notifications, notifications, R.string.settings_notifications_hint))
        add(SearchEntry(R.string.settings_bubbles, notifications, R.string.settings_bubbles_hint))
        add(SearchEntry(R.string.settings_live_notifications, notifications, R.string.settings_live_notifications_hint))
        add(SearchEntry(R.string.settings_live_channels, notifications, R.string.settings_live_channels_summary))
        val channels = SettingsPage.Channels
        add(SearchEntry(R.string.settings_channel_tabs, channels, R.string.settings_channel_tabs_hint))
        add(SearchEntry(R.string.settings_unread_title_bar, channels, R.string.settings_unread_title_bar_hint))
        val account = SettingsPage.Account
        add(SearchEntry(R.string.settings_group_accounts, account, also = listOf(R.string.logout, R.string.account_add_short)))
        add(SearchEntry(R.string.settings_group_backup, account, also = listOf(R.string.backup_export, R.string.backup_import)))
        val about = SettingsPage.About
        add(SearchEntry(R.string.settings_changelog, about, R.string.settings_changelog_summary))
        add(SearchEntry(R.string.settings_source_code, about, R.string.settings_source_code_summary))
        add(SearchEntry(R.string.settings_report_issue, about, R.string.settings_report_issue_summary))
        add(SearchEntry(R.string.settings_security, about, R.string.settings_security_summary))
        add(SearchEntry(R.string.settings_privacy, about, R.string.settings_privacy_summary))
        add(SearchEntry(R.string.settings_credits, about, R.string.settings_credits_summary))
        // Only the GitHub APK has this switch.
        if (BuildConfig.UPDATE_CHECK) add(SearchEntry(R.string.settings_update_check, about, R.string.settings_update_check_hint))
    }
}

/**
 * Twitch's login page over the settings. [signedOut] starts without Twitch's session, needed for
 * adding an account but not for logging the active one in again.
 */
private data class TwitchLogin(val url: String, val signedOut: Boolean)

/** The current position in the settings: search, category, sub-page. */
private data class SettingsPlace(
    val page: SettingsPage? = null,
    val subPage: SettingsSubPage? = null,
    /** The search is open; a category opened from it returns to it. */
    val searching: Boolean = false,
) {
    /** Decides whether a transition goes forward or back. */
    val depth: Int get() = (if (searching) 1 else 0) + (if (page != null) 1 else 0) + (if (subPage != null) 1 else 0)
}

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var subPage by rememberSaveable { mutableStateOf<SettingsSubPage?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // The setting a search result opened, highlighted until the page is left.
    var target by rememberSaveable { mutableStateOf<Int?>(null) }
    // Twitch's login page over the settings, while adding an account or logging in again.
    var addAccount by remember { mutableStateOf<TwitchLogin?>(null) }
    var addFailed by remember { mutableStateOf<String?>(null) }
    val goBack = {
        when {
            addAccount != null -> addAccount = null
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
    val auth by vm.authState.collectAsStateWithLifecycle()
    val account = (auth as? AuthState.LoggedIn)?.account
    val update by vm.availableUpdate.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
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

    val place = SettingsPlace(page, subPage, searching)
    // One step up. From the category list, leaving the settings is animated by the parent screen.
    val backTo = when {
        subPage != null -> place.copy(subPage = null)
        page != null -> place.copy(page = null)
        searching -> place.copy(searching = false)
        else -> null
    }
    rememberPredictiveTransition(place, backTo, onBack = goBack, label = "settings-page").AnimatedContent(
        transitionSpec = { slideBetweenScreens(forward = targetState.depth > initialState.depth) },
    ) { (current, currentSub, currentSearching) ->
        if (current == null && currentSearching) {
            SearchPage(
                query = query,
                onQuery = { query = it },
                onOpen = { entry ->
                    page = entry.page
                    target = entry.key
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
        // Search is only offered on the category list.
        val onSearch = if (current == null && currentSub == null) ({ searching = true }) else null
        SettingsPageScaffold(title = title, onBack = goBack, onSearch = onSearch, snackbar = snackbar) {
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
                SettingsSubPage.LiveChannels -> LiveChannelsPage(vm)
                SettingsSubPage.Rules -> RulesPage(vm)
                SettingsSubPage.Credits -> CreditsPage()
                SettingsSubPage.Changelog -> {
                    val releases by vm.releases.collectAsStateWithLifecycle()
                    ChangelogPage(releases, BuildConfig.VERSION_NAME)
                }
                // Gone once the new version is installed.
                SettingsSubPage.Update -> update?.let { UpdatePage(it) }
                null -> when (current) {
                    null -> {
                        update?.let { UpdateCard(it, BuildConfig.VERSION_NAME) { subPage = SettingsSubPage.Update } }
                        Home(account, vm.imageLoader) { page = it }
                    }
                    SettingsPage.Appearance -> AppearancePage(settings, vm)
                    SettingsPage.Chat -> ChatPage(settings, vm) { subPage = it }
                    SettingsPage.Emotes -> EmotesPage(settings, vm)
                    SettingsPage.Filters -> FiltersPage(settings, vm) { subPage = it }
                    SettingsPage.Notifications -> NotificationsPage(settings, vm) { subPage = it }
                    SettingsPage.Channels -> ChannelsPage(vm, settings)
                    SettingsPage.Stats -> StatsPage(vm) { page = SettingsPage.Channels }
                    SettingsPage.Account -> {
                        AccountPage(
                            vm,
                            onAddAccount = { addAccount = TwitchLogin(vm.addAccountUrl(), signedOut = true) },
                            // Same account: Twitch's session can stay, it only asks to grant the
                            // rest.
                            onReauthorize = { addAccount = TwitchLogin(vm.reauthorizeUrl(), signedOut = false) },
                        )
                        BackupGroup(vm)
                    }
                    SettingsPage.Support -> SupportPage(vm)
                    SettingsPage.About -> AboutPage(settings, vm) { subPage = it }
                }
            }
          }
        }
    }
}

/**
 * Search over all settings: a field in the title bar and the matches below, each with its category.
 * A result opens its category at the setting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchPage(query: String, onQuery: (String) -> Unit, onOpen: (SearchEntry) -> Unit, onBack: () -> Unit) {
    val resources = LocalResources.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val results = remember(query, resources) {
        fun text(res: Int) = SettingsSearch.plainTitle(resources.getString(res))
        val matching = SEARCH_INDEX.filter { e ->
            SettingsSearch.matches(query, (listOfNotNull(e.title, e.hint, e.page.title) + e.also).map(::text))
        }
        // Titles matching the query come before entries that only mention it.
        val (named, mentioned) = matching.partition { SettingsSearch.matches(query, listOf(text(it.title))) }
        (named + mentioned).map { it to text(it.title) }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                title = {
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
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
                actions = {
                    if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Default.Close, stringResource(R.string.settings_search_clear))
                    }
                },
            )
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
                results.forEach { (entry, title) ->
                    item {
                        ListItem(
                            headlineContent = { Text(title) },
                            // A category found as itself needs no category below.
                            supportingContent = if (entry.key == null) null else ({ Text(stringResource(entry.page.title)) }),
                            leadingContent = { CategoryIcon(entry.page.icon) },
                            colors = transparentItem(),
                            modifier = Modifier.clickable { onOpen(entry) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
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
                        if (onSearch != null) IconButton(onClick = onSearch) {
                            Icon(Icons.Default.Search, stringResource(R.string.settings_search))
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
            content()
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Home(account: Account?, imageLoader: ImageLoader, open: (SettingsPage) -> Unit) {
    SettingsGroup {
        SettingsPage.entries.filter { it.shown }.forEach { p ->
            val isAccount = p == SettingsPage.Account && account != null
            item {
                ListItem(
                    headlineContent = { Text(stringResource(p.title), fontWeight = FontWeight.Medium) },
                    supportingContent = {
                        Text(if (isAccount) account.name else stringResource(p.summary))
                    },
                    // The active account's avatar instead of an icon.
                    leadingContent = {
                        if (isAccount) AccountRowIcon(account, imageLoader) else CategoryIcon(p.icon)
                    },
                    colors = transparentItem(),
                    modifier = Modifier.clickable { open(p) },
                )
            }
        }
    }
}
