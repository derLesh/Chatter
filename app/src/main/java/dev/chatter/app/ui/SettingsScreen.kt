package dev.chatter.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import dev.chatter.app.BuildConfig
import dev.chatter.app.R
import dev.chatter.app.auth.Account
import dev.chatter.app.auth.AuthState
import dev.chatter.app.badges.BadgeProvider
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.ChatRule
import dev.chatter.app.chat.ImageLinks
import dev.chatter.app.chat.MessageBody
import dev.chatter.app.chat.MessageKind
import dev.chatter.app.chat.RuleAction
import dev.chatter.app.chat.RuleEngine
import dev.chatter.app.chat.RuleTarget
import dev.chatter.app.chat.Segment
import dev.chatter.app.emotes.EmoteProvider
import dev.chatter.app.net.HelixBlockedUser
import dev.chatter.app.service.ChatNotifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import android.text.format.Formatter
import dev.chatter.app.settings.MobileData
import dev.chatter.app.stats.Stats
import dev.chatter.app.ui.settings.BackgroundCard
import dev.chatter.app.settings.Settings
import dev.chatter.app.settings.SettingsBackup
import dev.chatter.app.settings.ThemeMode
import dev.chatter.app.settings.TapAction
import dev.chatter.app.settings.TimestampFormat
import dev.chatter.app.ui.changelog.ChangelogPage
import dev.chatter.app.crash.Crash
import dev.chatter.app.crash.DeviceInfo
import dev.chatter.app.ui.channels.AddChannelDialog
import dev.chatter.app.ui.channels.CombineChannelsDialog
import dev.chatter.app.ui.channels.ManageChannelsPage
import dev.chatter.app.ui.channels.OfferUndoRemoval
import dev.chatter.app.ui.channels.RenameChannelDialog
import dev.chatter.app.ui.chat.ChatStyle
import dev.chatter.app.ui.chat.MessageRow
import dev.chatter.app.ui.settings.LocalSettingsTarget
import dev.chatter.app.ui.settings.SettingsSearch
import dev.chatter.app.ui.update.UpdateCard
import dev.chatter.app.ui.update.UpdatePage
import dev.chatter.app.ui.settings.AccountPage
import dev.chatter.app.ui.settings.AccountRowIcon
import dev.chatter.app.ui.settings.AddKeywordDialog
import dev.chatter.app.ui.settings.BlockUserDialog
import dev.chatter.app.ui.settings.CategoryIcon
import dev.chatter.app.ui.settings.ConfirmUnblockDialog
import dev.chatter.app.ui.settings.LinkItem
import dev.chatter.app.ui.settings.RuleDialog
import dev.chatter.app.ui.settings.SettingsGroup
import dev.chatter.app.ui.settings.transparentItem
import dev.chatter.app.ui.theme.NameColorPalette
import dev.chatter.app.ui.theme.highlightBackground
import dev.chatter.app.ui.theme.highlightColor
import dev.chatter.app.ui.theme.isAppInDarkTheme
import dev.chatter.app.ui.theme.readableNameColor
import dev.chatter.app.util.AppIcon
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Top level of the settings, like the Android settings app: categories that open a page. */
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

/** A page opened from inside a category, one level below [SettingsPage]. */
private enum class SettingsSubPage(val title: Int) {
    BlockedUsers(R.string.settings_blocked_users),
    MentionKeywords(R.string.settings_keywords),
    MuteKeywords(R.string.settings_mute_keywords),
    ImageHosts(R.string.settings_image_hosts),
    Rules(R.string.settings_rules),
    Changelog(R.string.settings_changelog),
    Update(R.string.update_page_title),
    Credits(R.string.settings_credits),
}

/**
 * One setting as the search finds it: the title it is shown under, the page it is on, and what
 * else it may be looked for by. [key] is the tile or group it opens to — null for a page itself.
 */
private class SearchEntry(
    val title: Int,
    val page: SettingsPage,
    val hint: Int? = null,
    val also: List<Int> = emptyList(),
    val key: Int? = title,
)

/**
 * Everything the search finds, in the order the pages show it. A setting is found by the title
 * its tile is keyed with (`item(R.string.x)`), so a new setting that should be found needs both:
 * the key on its tile and a line here.
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
        add(SearchEntry(R.string.settings_privacy, about, R.string.settings_privacy_summary))
        add(SearchEntry(R.string.settings_credits, about, R.string.settings_credits_summary))
        // Only the APK from GitHub has the switch; see the about page.
        if (BuildConfig.UPDATE_CHECK) add(SearchEntry(R.string.settings_update_check, about, R.string.settings_update_check_hint))
    }
}

/**
 * Twitch's login page over the settings. [signedOut] starts it without Twitch's session, which
 * adding an account needs and logging the active account in again does not.
 */
private data class TwitchLogin(val url: String, val signedOut: Boolean)

/** Where in the settings the user is: the search, a category, a page inside it. */
private data class SettingsPlace(
    val page: SettingsPage? = null,
    val subPage: SettingsSubPage? = null,
    /** The search is open; a category opened from it goes back to it. */
    val searching: Boolean = false,
) {
    /** How deep this is, which is what says whether a move is forward or back. */
    val depth: Int get() = (if (searching) 1 else 0) + (if (page != null) 1 else 0) + (if (subPage != null) 1 else 0)
}

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var subPage by rememberSaveable { mutableStateOf<SettingsSubPage?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // The setting a search result opened its page at, lit up there until the page is left.
    var target by rememberSaveable { mutableStateOf<Int?>(null) }
    // Twitch's login page, shown over the settings while an account is added or logged in
    // again.
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
    // The channel page can remove a channel, and offers it back like the chat does.
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
    // One step up. On the list of categories there is none left: leaving the settings is the
    // screen around them's to animate.
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
        // The about page carries its own icon and app name, so it gets no title bar heading.
        val title = when {
            currentSub != null -> currentSub.title
            current == SettingsPage.About -> null
            else -> current?.title ?: R.string.settings
        }
        // Only the list of categories is searched from; a category is already where to look.
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
                SettingsSubPage.Rules -> RulesPage(vm)
                SettingsSubPage.Credits -> CreditsPage()
                SettingsSubPage.Changelog -> {
                    val releases by vm.releases.collectAsStateWithLifecycle()
                    ChangelogPage(releases, BuildConfig.VERSION_NAME)
                }
                // Gone once the new version is installed, which leaves nothing to show here.
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
                            // The same account again: Twitch's session can stay, it only asks to allow the rest.
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
 * The search over every setting: a field in the title bar, and underneath whatever matches, each
 * with the category it is in. A result opens its category at the setting.
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
        // What is called what was typed comes before what only mentions it.
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
                            // A category found as itself needs no category underneath.
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

/** One settings page: its own collapsing large title bar and scrolling content. */
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
        // Only the large bar collapses. A page without one (the about page) has nothing for this
        // connection to move, and it would swallow every scroll rather than pass it on.
        modifier = if (title != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
        topBar = {
            // Without a heading a large bar would just be empty space, so it shrinks to a plain one.
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

// ---- Pages ------------------------------------------------------------------------------------

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
                    // The account the app is chatting as says more with its own face on it than
                    // any icon could, and it is the one row here that is about a person.
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

/**
 * Supporting Chatter, which happens outside the app: GitHub Sponsors takes the money, and there
 * is no way for it to know which Twitch account a sponsor has — so the second row is how somebody
 * says so, with their Twitch id already filled in by the app that knows it.
 */
@Composable
private fun SupportPage(vm: MainViewModel) {
    SettingsGroup {
        item { LinkItem(R.string.settings_sponsor, R.string.settings_sponsor_summary, SPONSOR_URL) }
        item {
            LinkItem(
                stringResource(R.string.settings_sponsor_claim),
                stringResource(R.string.settings_sponsor_claim_summary),
                claimUrl(vm.ownTwitchId, vm.ownLogin),
            )
        }
    }
    Text(
        stringResource(R.string.settings_sponsor_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp),
    )
}

/**
 * A new issue on the repository with the Twitch account already in it — the one field GitHub
 * Sponsors cannot tell anybody. Whoever opens it is who GitHub says they are, which is what lets
 * the badge be handed out without a person in the middle.
 */
private fun claimUrl(twitchId: String?, login: String): String =
    Uri.parse("$REPO_URL/issues/new").buildUpon()
        .appendQueryParameter("template", "supporter.yml")
        .appendQueryParameter("title", "Supporter badge for $login")
        .appendQueryParameter("twitch-id", twitchId.orEmpty())
        .appendQueryParameter("twitch-name", login)
        .build()
        .toString()

@Composable
private fun AppearancePage(settings: Settings, vm: MainViewModel) {
    val dark = isAppInDarkTheme()
    SettingsGroup(R.string.settings_group_colors) {
        item(R.string.settings_theme) {
            ListItem(
                // The buttons are the row. Three options of one word each say at a glance what a
                // sheet would only say once it is open, and "System / Light / Dark" under the
                // "Colors" heading needs no second word above it saying that it is the theme.
                headlineContent = {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        THEME_MODES.forEachIndexed { i, (mode, label) ->
                            SegmentedButton(
                                selected = settings.themeMode == mode,
                                onClick = { vm.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(i, THEME_MODES.size),
                            ) { Text(stringResource(label)) }
                        }
                    }
                },
                colors = transparentItem(),
            )
        }
        item(R.string.settings_dynamic_color) { SwitchItem(R.string.settings_dynamic_color, settings.dynamicColor, vm::setDynamicColor, R.string.settings_dynamic_color_hint) }
        // A light theme has no black to turn anything to, so the switch is only there while the
        // app is dark — whether the user chose Dark or the phone is dark under System.
        if (dark) {
            item(R.string.settings_pure_black) {
                SwitchItem(R.string.settings_pure_black, settings.pureBlack, vm::setPureBlack, R.string.settings_pure_black_hint)
            }
        }
        item(R.string.settings_highlight_color) { HighlightColorPicker(settings.highlightColor, vm::setHighlightColor) }
        item(R.string.settings_name_colors) { NameColorPicker(settings.nameColors, vm::setNameColors) }
        item(R.string.settings_app_icon) { AppIconPicker() }
    }

    SettingsGroup(R.string.settings_group_text) {
        item(R.string.settings_font_size) { TextSizeItem(settings, vm) }
        item(R.string.settings_timestamps) { TimestampPicker(settings.timestamps, vm::setTimestamps) }
    }

    // How a message itself is drawn, which is what somebody looking for "the chat looks wrong"
    // comes here for — the emotes in it have a category of their own.
    SettingsGroup(R.string.settings_group_messages) {
        item(R.string.settings_alternate_background) {
            SwitchItem(
                R.string.settings_alternate_background, settings.alternateBackground,
                vm::setAlternateBackground, R.string.settings_alternate_background_hint,
            )
        }
        item(R.string.settings_smooth_scrolling) {
            SwitchItem(
                R.string.settings_smooth_scrolling, settings.smoothScrolling,
                vm::setSmoothScrolling, R.string.settings_smooth_scrolling_hint,
            )
        }
        item(R.string.settings_show_deleted) { SwitchItem(R.string.settings_show_deleted, settings.showDeleted, vm::setShowDeleted, R.string.settings_show_deleted_hint) }
        item(R.string.settings_first_messages) {
            SwitchItem(
                R.string.settings_first_messages, settings.highlightFirstMessages,
                vm::setHighlightFirstMessages, R.string.settings_first_messages_hint,
            )
        }
    }

    // The two that are about the phone rather than the chat, together instead of one group each.
    SettingsGroup(R.string.settings_group_device) {
        item(R.string.settings_keep_screen_on) {
            SwitchItem(
                R.string.settings_keep_screen_on, settings.keepScreenOn,
                vm::setKeepScreenOn, R.string.settings_keep_screen_on_hint,
            )
        }
        item(R.string.settings_haptics) {
            SwitchItem(
                R.string.settings_haptics, settings.haptics,
                vm::setHaptics, R.string.settings_haptics_hint,
            )
        }
    }
}

/**
 * Everything about emotes and badges in one place. They used to be split down the middle — the
 * emotes under the appearance, the providers they come from under the chat — which meant
 * turning one provider off was never where anybody looked for it.
 */
@Composable
private fun EmotesPage(settings: Settings, vm: MainViewModel) {
    SettingsGroup(R.string.settings_group_emotes) {
        item(R.string.settings_emotes_enabled) { SwitchItem(R.string.settings_emotes_enabled, settings.emotesEnabled, vm::setEmotesEnabled, R.string.settings_emotes_new_messages_hint) }
        item(R.string.settings_animated_emotes) { SwitchItem(R.string.settings_animated_emotes, settings.animatedEmotes, vm::setAnimatedEmotes) }
        // Only says something while they move at all.
        if (settings.animatedEmotes) {
            item(R.string.settings_slow_idle_emotes) {
                SwitchItem(R.string.settings_slow_idle_emotes, settings.slowIdleEmotes, vm::setSlowIdleEmotes, R.string.settings_slow_idle_emotes_hint)
            }
        }
        item(R.string.settings_zero_width) { SwitchItem(R.string.settings_zero_width, settings.zeroWidthEmotes, vm::setZeroWidthEmotes, R.string.settings_zero_width_hint) }
        item(R.string.settings_unlisted_7tv) { SwitchItem(R.string.settings_unlisted_7tv, settings.showUnlisted7tv, vm::setShowUnlisted7tv, R.string.settings_unlisted_7tv_hint) }
        item(R.string.settings_seventv_events) { SwitchItem(R.string.settings_seventv_events, settings.sevenTvEvents, vm::setSevenTvEvents, R.string.settings_seventv_events_hint) }
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

/**
 * Everything that keeps something out of the chat, including the Twitch block list — which sat
 * under the account, where nobody hiding a chatter would have gone looking.
 */
@Composable
private fun FiltersPage(settings: Settings, vm: MainViewModel, open: (SettingsSubPage) -> Unit) {
    val blocked by vm.blockedUsers.collectAsStateWithLifecycle()
    SettingsGroup {
        item(R.string.settings_mute_keywords) {
            KeywordListItem(
                title = R.string.settings_mute_keywords,
                summary = R.string.settings_mute_keywords_summary,
                words = settings.muteKeywords,
                onClick = { open(SettingsSubPage.MuteKeywords) },
            )
        }
        item(R.string.settings_rules) {
            val rules by vm.rules.collectAsStateWithLifecycle()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_rules)) },
                supportingContent = {
                    Text(
                        if (rules.isEmpty()) stringResource(R.string.settings_rules_summary)
                        else pluralStringResource(R.plurals.settings_rules_count, rules.size, rules.size)
                    )
                },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                colors = transparentItem(),
                modifier = Modifier.clickable { open(SettingsSubPage.Rules) },
            )
        }
        item(R.string.settings_blocked_users) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_blocked_users)) },
                supportingContent = {
                    Text(
                        if (blocked.isEmpty()) stringResource(R.string.settings_blocked_none)
                        else pluralStringResource(R.plurals.settings_blocked_count, blocked.size, blocked.size)
                    )
                },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                colors = transparentItem(),
                modifier = Modifier.clickable { open(SettingsSubPage.BlockedUsers) },
            )
        }
    }
}

/** Chat text size with a live preview of a chat line at that size. */
@Composable
private fun TextSizeItem(settings: Settings, vm: MainViewModel) {
    var size by remember(settings.fontSize) { mutableFloatStateOf(settings.fontSize) }
    val scheme = MaterialTheme.colorScheme
    val dark = isAppInDarkTheme()
    val sampleText = stringResource(R.string.settings_text_size_sample)
    val sample = remember(sampleText) {
        ChatItem(
            id = "preview", channel = "", kind = MessageKind.Chat, timestamp = System.currentTimeMillis(),
            login = "chatter", displayName = "Chatter", color = 0xFF1E90FF.toInt(),
            body = MessageBody.of(listOf(Segment.Text(sampleText))), text = sampleText,
        )
    }
    val style = ChatStyle(
        fontSize = size.roundToInt().toFloat(),
        timestamps = settings.timestamps,
        dark = dark,
        secondaryText = scheme.onSurfaceVariant,
        linkColor = scheme.primary,
        mentionBackground = Color.Transparent,
        alternateBackground = null,
        noticeBackground = Color.Transparent,
        firstMessageBackground = null,
        accent = scheme.primary,
        nameColors = settings.nameColors,
        nicknames = emptyMap(),
        haptics = false,
        imageHosts = emptyList(),
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_font_size, size.roundToInt())) },
        supportingContent = {
            Column {
                Slider(
                    value = size,
                    onValueChange = { size = it },
                    onValueChangeFinished = { vm.setFontSize(size.roundToInt().toFloat()) },
                    valueRange = 10f..24f,
                    steps = 13,
                )
                Surface(
                    color = scheme.surface,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Box(Modifier.padding(vertical = 8.dp)) {
                        MessageRow(sample, style, vm.imageLoader, onGesture = null)
                    }
                }
            }
        },
        colors = transparentItem(),
    )
}

@Composable
private fun ChatPage(settings: Settings, vm: MainViewModel, open: (SettingsSubPage) -> Unit) {
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
    // What the app offers while typing.
    SettingsGroup(R.string.settings_group_input) {
        item(R.string.settings_emote_suggestions) { SwitchItem(R.string.settings_emote_suggestions, settings.emoteSuggestions, vm::setEmoteSuggestions, R.string.settings_emote_suggestions_hint) }
        item(R.string.settings_user_suggestions) { SwitchItem(R.string.settings_user_suggestions, settings.userSuggestions, vm::setUserSuggestions, R.string.settings_user_suggestions_hint) }
        item(R.string.settings_mention_with_at) { SwitchItem(R.string.settings_mention_with_at, settings.mentionWithAt, vm::setMentionWithAt, R.string.settings_mention_with_at_hint) }
    }
    // What a tap or a swipe on the chat does.
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

@Composable
private fun NotificationsPage(settings: Settings, vm: MainViewModel, open: (SettingsSubPage) -> Unit) {
    val context = LocalContext.current
    val stop by vm.backgroundStop.collectAsStateWithLifecycle()
    val battery by vm.batteryRestrictions.collectAsStateWithLifecycle()
    // The card's button leads into the system settings; coming back is when to look again.
    LifecycleResumeEffect(Unit) {
        vm.refreshBatteryRestrictions()
        onPauseOrDispose { }
    }
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
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }) { Text(stringResource(R.string.open)) }
                },
                colors = transparentItem(),
            )
        }
    }
    SettingsGroup(R.string.settings_group_bubbles) {
        item(R.string.settings_bubbles) { SwitchItem(R.string.settings_bubbles, settings.bubbles, vm::setBubbles, R.string.settings_bubbles_hint) }
    }
}

/**
 * The Twitch block list: everyone here is hidden from the chat until they are unblocked. Blocking
 * is a deliberate act, so undoing it asks for confirmation.
 */
@Composable
private fun BlockedUsersPage(vm: MainViewModel) {
    val blocked by vm.blockedUsers.collectAsStateWithLifecycle()
    var unblockTarget by remember { mutableStateOf<HelixBlockedUser?>(null) }
    var showBlock by remember { mutableStateOf(false) }

    SettingsGroup {
        if (blocked.isEmpty()) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_blocked_none)) },
                    supportingContent = { Text(stringResource(R.string.settings_blocked_hint)) },
                    colors = transparentItem(),
                )
            }
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_report_user)) },
                supportingContent = { Text(stringResource(R.string.settings_report_user_summary)) },
                leadingContent = { Icon(painterResource(R.drawable.ic_report_flag), contentDescription = null) },
                colors = transparentItem(),
            )
        }
        blocked.forEach { user ->
            item {
                ListItem(
                    headlineContent = { Text(user.displayName.ifEmpty { user.userLogin }) },
                    supportingContent = { Text("@" + user.userLogin) },
                    trailingContent = {
                        OutlinedButton(onClick = { unblockTarget = user }) {
                            Text(stringResource(R.string.action_unblock))
                        }
                    },
                    colors = transparentItem(),
                )
            }
        }
    }
    Button(onClick = { showBlock = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.block_user_title))
    }

    unblockTarget?.let { user ->
        ConfirmUnblockDialog(
            name = user.displayName.ifEmpty { user.userLogin },
            onConfirm = { vm.unblock(user) },
            onDismiss = { unblockTarget = null },
        )
    }
    if (showBlock) {
        BlockUserDialog(
            search = vm::searchChannels,
            alreadyBlocked = blocked.mapTo(HashSet()) { it.userLogin.lowercase() },
            imageLoader = vm.imageLoader,
            onBlock = vm::blockByLogin,
            onDismiss = { showBlock = false },
        )
    }
}

/**
 * The user's highlight rules. Order matters — a hide beats everything below it — so they are
 * listed the way they are applied.
 */
@Composable
private fun RulesPage(vm: MainViewModel) {
    val rules by vm.rules.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ChatRule?>(null) }
    var creating by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    SettingsGroup {
        if (rules.isEmpty()) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.rules_empty)) },
                    supportingContent = { Text(stringResource(R.string.rules_empty_hint)) },
                    colors = transparentItem(),
                )
            }
        }
        rules.forEach { rule ->
            item {
                ListItem(
                    headlineContent = { Text(rule.pattern, fontFamily = if (rule.regex) FontFamily.Monospace else null) },
                    supportingContent = {
                        Column {
                            Text(ruleSummary(rule))
                            if (rule.regex && RuleEngine.skips(rule.pattern)) {
                                Text(stringResource(R.string.rule_unusable), color = scheme.error)
                            }
                        }
                    },
                    leadingContent = {
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(rule.color?.let { Color(it) } ?: scheme.surfaceContainerHighest),
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = rule.enabled, onCheckedChange = { vm.setRuleEnabled(rule, it) })
                            IconButton(onClick = { vm.deleteRule(rule) }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.rule_delete))
                            }
                        }
                    },
                    colors = transparentItem(),
                    modifier = Modifier.clickable { editing = rule },
                )
            }
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.rule_new)) },
                leadingContent = { CategoryIcon(Icons.Default.Add) },
                colors = transparentItem(),
                modifier = Modifier.clickable { creating = true },
            )
        }
    }

    if (creating || editing != null) {
        RuleDialog(
            rule = editing,
            onSave = vm::saveRule,
            onDismiss = { creating = false; editing = null },
        )
    }
}

/** "Message - Highlight - #forsen": what a rule does, in one line. */
@Composable
private fun ruleSummary(rule: ChatRule): String {
    val target = stringResource(
        when (rule.target) {
            RuleTarget.Message -> R.string.rule_target_message
            RuleTarget.Author -> R.string.rule_target_author
            RuleTarget.Any -> R.string.rule_target_any
        }
    )
    val action = stringResource(
        when (rule.action) {
            RuleAction.Highlight -> R.string.rule_action_highlight
            RuleAction.Notify -> R.string.rule_action_notify
            RuleAction.Hide -> R.string.rule_action_hide
        }
    )
    return listOfNotNull(target, action, rule.channel?.let { "#$it" }).joinToString(" · ")
}

@Composable
private fun ChannelsPage(vm: MainViewModel, settings: Settings) {
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
    // Null while no dialog is open; the key of the combined chat being changed, or "" for a new one.
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

/**
 * What the user has done in chat so far. Everything shown here was counted on this device and
 * never leaves it, which is also why it can be thrown away in one go at the bottom.
 */
@Composable
private fun StatsPage(vm: MainViewModel, onChannels: () -> Unit) {
    val context = LocalContext.current
    val stats by vm.stats.collectAsStateWithLifecycle()
    val info by vm.channelInfo.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }
    val busiest = remember(stats) { stats.busiestChannels.take(5) }
    val week = remember(stats) { stats.backgroundByChannel(Stats.KEEP_DAYS) }
    val today = remember(stats) { stats.backgroundByChannel(1).toMap() }
    val outliers = remember(week) { Stats.outliers(week) }
    val bytesToday = remember(stats) { stats.trafficOver(1) }
    val bytesWeek = remember(stats) { stats.trafficOver(Stats.KEEP_DAYS) }
    val size = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }

    SettingsGroup(R.string.settings_stats_group_messages) {
        item { StatRow(R.string.settings_stats_sent, formatNumber(stats.sent)) }
        item { StatRow(R.string.settings_stats_received, formatNumber(stats.received)) }
        item { StatRow(R.string.settings_stats_mentions, formatNumber(stats.mentions)) }
    }
    SettingsGroup(R.string.settings_stats_group_days) {
        item {
            StatRow(
                R.string.settings_stats_active_days,
                pluralStringResource(R.plurals.stats_days, stats.activeDays.size, stats.activeDays.size),
            )
        }
        item {
            val streak = remember(stats) { stats.streak() }
            StatRow(
                R.string.settings_stats_streak,
                pluralStringResource(R.plurals.stats_days, streak, streak),
            )
        }
        if (stats.since > 0) {
            item { StatRow(R.string.settings_stats_since, formatDay(stats.since)) }
        }
    }
    SettingsGroup(R.string.settings_stats_group_channels) {
        if (busiest.isEmpty()) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_stats_no_channels)) },
                    colors = transparentItem(),
                )
            }
        }
        busiest.forEach { (login, count) ->
            item {
                ListItem(
                    headlineContent = { Text(info[login]?.displayName ?: login) },
                    trailingContent = { Text(formatNumber(count), fontWeight = FontWeight.Medium) },
                    colors = transparentItem(),
                )
            }
        }
    }
    // What staying joined costs while the app is closed: which channel brings in how much, so that
    // whether to keep a very busy one overnight is a choice the user can actually make.
    SettingsGroup(R.string.settings_stats_group_background) {
        if (week.isEmpty()) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_stats_background_none)) },
                    colors = transparentItem(),
                )
            }
        }
        week.take(BACKGROUND_CHANNELS_SHOWN).forEach { (login, count) ->
            item {
                val busy = login in outliers
                ListItem(
                    headlineContent = { Text(info[login]?.displayName ?: login) },
                    supportingContent = {
                        Column {
                            Text(
                                stringResource(
                                    R.string.settings_stats_today_week,
                                    formatNumber(today[login] ?: 0), formatNumber(count),
                                )
                            )
                            if (busy) {
                                Text(
                                    stringResource(R.string.settings_stats_background_busy),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    trailingContent = if (busy) {
                        { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) }
                    } else null,
                    colors = transparentItem(),
                    modifier = if (busy) Modifier.clickable(onClick = onChannels) else Modifier,
                )
            }
        }
    }
    SettingsGroup(R.string.settings_stats_group_data) {
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_stats_data_open)) },
                supportingContent = {
                    Text(stringResource(R.string.settings_stats_today_week, size(bytesToday.open), size(bytesWeek.open)))
                },
                colors = transparentItem(),
            )
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_stats_data_background)) },
                supportingContent = {
                    Text(stringResource(R.string.settings_stats_today_week, size(bytesToday.background), size(bytesWeek.background)))
                },
                colors = transparentItem(),
            )
        }
    }
    SettingsGroup {
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_stats_reset)) },
                supportingContent = { Text(stringResource(R.string.settings_stats_reset_hint)) },
                trailingContent = {
                    OutlinedButton(onClick = { confirmReset = true }) {
                        Text(stringResource(R.string.settings_stats_reset_action))
                    }
                },
                colors = transparentItem(),
            )
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.settings_stats_reset)) },
            text = { Text(stringResource(R.string.settings_stats_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; vm.resetStats() }) {
                    Text(stringResource(R.string.settings_stats_reset_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun StatRow(label: Int, value: String) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        trailingContent = { Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium) },
        colors = transparentItem(),
    )
}

/** How many channels the background figures list; the ones further down cost little by then. */
private const val BACKGROUND_CHANNELS_SHOWN = 8

/** Grouped the way the phone's language groups them, so six digits stay readable. */
private fun formatNumber(n: Long): String = NumberFormat.getIntegerInstance().format(n)

private fun formatDay(at: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at))

/**
 * Opens the bug report form with what Chatter knows already filled in: its version, Android's and
 * the phone's. If Chatter has crashed, it first offers to copy what it wrote down about that, to
 * be pasted into the form — the form takes it as a field, but a stack trace is too long for a
 * link, and nothing of it should leave the phone without the user putting it there themselves.
 */
@Composable
private fun ReportIssueItem(vm: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var crash by remember { mutableStateOf<Crash?>(null) }
    val open = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(bugReportUrl(vm.device)))) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_report_issue)) },
        supportingContent = { Text(stringResource(R.string.settings_report_issue_summary)) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
        colors = transparentItem(),
        modifier = Modifier.clickable {
            scope.launch {
                val last = vm.lastCrash()
                if (last == null) open() else crash = last
            }
        },
    )
    crash?.let { last ->
        AlertDialog(
            onDismissRequest = { crash = null },
            title = { Text(stringResource(R.string.crash_attach_title)) },
            text = {
                val at = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last.at))
                Text(stringResource(R.string.crash_attach_text, at))
            },
            confirmButton = {
                val label = stringResource(R.string.crash_clip_label)
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText(label, last.text))
                    crash = null
                    open()
                }) { Text(stringResource(R.string.crash_attach_copy)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    crash = null
                    open()
                }) { Text(stringResource(R.string.crash_attach_skip)) }
            },
        )
    }
}

/** The bug report template, its fields about the app and the phone filled in. */
private fun bugReportUrl(device: DeviceInfo): String =
    Uri.parse("$REPO_URL/issues/new").buildUpon()
        .appendQueryParameter("template", "bug.yml")
        .appendQueryParameter("version", "${device.appVersion} (${device.versionCode})")
        .appendQueryParameter("android", "${device.android} (API ${device.sdk})")
        .appendQueryParameter("device", device.device)
        .build()
        .toString()

@Composable
private fun AboutPage(settings: Settings, vm: MainViewModel, open: (SettingsSubPage) -> Unit) {
    // The wordmark, which already says the name, so no heading repeats it underneath.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        Image(
            painterResource(R.drawable.ic_chatter_wordmark),
            contentDescription = stringResource(R.string.app_name),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.width(208.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SettingsGroup {
        item(R.string.settings_changelog) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_changelog)) },
                supportingContent = { Text(stringResource(R.string.settings_changelog_summary)) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                colors = transparentItem(),
                modifier = Modifier.clickable { open(SettingsSubPage.Changelog) },
            )
        }
        item(R.string.settings_source_code) { LinkItem(R.string.settings_source_code, R.string.settings_source_code_summary, REPO_URL) }
        item(R.string.settings_report_issue) { ReportIssueItem(vm) }
        item(R.string.settings_privacy) { LinkItem(R.string.settings_privacy, R.string.settings_privacy_summary, PRIVACY_URL) }
        item(R.string.settings_credits) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_credits)) },
                supportingContent = { Text(stringResource(R.string.settings_credits_summary)) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                colors = transparentItem(),
                modifier = Modifier.clickable { open(SettingsSubPage.Credits) },
            )
        }
    }
    // Only the APK from GitHub has anything to switch here; Play keeps its installs up to date.
    if (BuildConfig.UPDATE_CHECK) SettingsGroup {
        item(R.string.settings_update_check) {
            SwitchItem(
                R.string.settings_update_check,
                settings.updateCheck,
                vm::setUpdateCheck,
                R.string.settings_update_check_hint,
            )
        }
    }
    // Twitch's branding rules ask every third-party client to say it is not one of theirs.
    Text(
        stringResource(R.string.settings_twitch_disclaimer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 20.dp),
    )
}

/**
 * Who Chatter is built on: the services it talks to and the libraries it is made of. Two long
 * lists that are read once out of curiosity, which is a page of their own rather than the tail
 * end of everything else the about page has to say.
 */
@Composable
private fun CreditsPage() {
    var shownLicense by remember { mutableStateOf<Dependency?>(null) }
    SettingsGroup(R.string.settings_credits_services) {
        CREDITS.forEach { (title, summary, url) ->
            item { LinkItem(title, summary, url) }
        }
    }
    SettingsGroup(R.string.settings_licenses) {
        DEPENDENCIES.forEach { dependency ->
            item {
                ListItem(
                    headlineContent = { Text(dependency.name) },
                    supportingContent = { Text(stringResource(dependency.license)) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                    colors = transparentItem(),
                    modifier = Modifier.clickable { shownLicense = dependency },
                )
            }
        }
    }
    shownLicense?.let { dependency ->
        LicenseSheet(dependency, onDismiss = { shownLicense = null })
    }
}

/**
 * Writing the whole configuration to a file and reading it back. The format is Chatter's own —
 * it is for moving to a new phone or keeping a copy before experimenting, not for importing
 * another client's settings.
 */
@Composable
private fun BackupGroup(vm: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<Int?>(null) }
    var pending by remember { mutableStateOf<SettingsBackup?>(null) }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME)) { uri ->
        if (uri != null) {
            val text = vm.exportBackup()
            scope.launch {
                status = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                            ?: error("no output stream")
                    }.fold({ R.string.backup_exported }, { R.string.backup_failed })
                }
            }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                }
                if (text == null) {
                    status = R.string.backup_failed
                    return@launch
                }
                // Nothing is written until the user has seen what the file would change.
                pending = vm.readBackup(text)
                if (pending == null) status = R.string.backup_invalid
            }
        }
    }

    pending?.let { backup ->
        val hosts = backup.settings?.imageHosts
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.backup_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.backup_confirm_text))
                    Text(
                        stringResource(
                            R.string.backup_confirm_counts,
                            backup.rules?.size ?: 0,
                            backup.nicknames?.size ?: 0,
                            backup.channels?.logins?.size ?: 0,
                        ),
                    )
                    // Which sites the app will fetch from is the one thing a backup decides that
                    // reaches outside the phone, so it is spelled out.
                    if (hosts != null) {
                        Text(
                            if (hosts.isEmpty()) stringResource(R.string.backup_confirm_no_hosts)
                            else stringResource(R.string.backup_confirm_hosts, hosts.joinToString(", ")),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    scope.launch {
                        vm.applyBackup(backup)
                        status = R.string.backup_imported
                    }
                }) { Text(stringResource(R.string.backup_restore)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    SettingsGroup(R.string.settings_group_backup) {
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.backup_export)) },
                supportingContent = { Text(stringResource(R.string.backup_export_hint)) },
                trailingContent = {
                    OutlinedButton(onClick = { export.launch(backupFileName()) }) {
                        Text(stringResource(R.string.backup_save))
                    }
                },
                colors = transparentItem(),
            )
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.backup_import)) },
                supportingContent = { Text(stringResource(R.string.backup_import_hint)) },
                trailingContent = {
                    OutlinedButton(onClick = { import.launch(arrayOf(BACKUP_MIME, "text/plain")) }) {
                        Text(stringResource(R.string.open))
                    }
                },
                colors = transparentItem(),
            )
        }
        status?.let { res ->
            item {
                ListItem(
                    headlineContent = { Text(stringResource(res), color = MaterialTheme.colorScheme.primary) },
                    colors = transparentItem(),
                )
            }
        }
    }
}

private const val BACKUP_MIME = "application/json"

/** "chatter-2026-09-20.json": dated, so several backups sit next to each other. */
private fun backupFileName(): String =
    "chatter-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"

/** One of the libraries Chatter ships, for the license listing, with the name and text of its license. */
private data class Dependency(
    val name: String,
    val url: String,
    val license: Int = R.string.license_apache2,
    val text: Int = R.raw.license_apache_2_0,
)

/** The libraries Chatter ships. Most are Apache 2.0 and share that license text. */
private val DEPENDENCIES = listOf(
    Dependency("Kotlin", "https://kotlinlang.org"),
    Dependency("Kotlin Coroutines", "https://github.com/Kotlin/kotlinx.coroutines"),
    Dependency("kotlinx.serialization", "https://github.com/Kotlin/kotlinx.serialization"),
    Dependency("AndroidX (Core, Activity, Lifecycle, DataStore)", "https://developer.android.com/jetpack/androidx"),
    Dependency("Jetpack Compose", "https://developer.android.com/jetpack/compose"),
    Dependency("OkHttp", "https://square.github.io/okhttp/"),
    Dependency("Coil", "https://coil-kt.github.io/coil/"),
    Dependency("RE2/J", "https://github.com/google/re2j", R.string.license_bsd3, R.raw.license_re2j),
)

/** The full license text of one dependency, plus a way to its project page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicenseSheet(dependency: Dependency, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // ~11 kB read once per sheet; kept out of the first frame so opening stays instant.
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        text = withContext(Dispatchers.IO) {
            runCatching {
                context.resources.openRawResource(dependency.text).bufferedReader().use { it.readText() }
            }.getOrDefault("")
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(dependency.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                stringResource(dependency.license),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dependency.url))) },
                modifier = Modifier.padding(top = 12.dp),
            ) { Text(stringResource(R.string.license_open_project)) }
            // Monospace keeps the license's own indentation and line breaks intact.
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }
}

/** The services Chatter builds on, each linking to where it comes from. */
private val CREDITS = listOf(
    Triple(R.string.settings_credits_twitch, R.string.settings_credits_twitch_summary, "https://twitch.tv"),
    Triple(R.string.settings_credits_seventv, R.string.settings_credits_seventv_summary, "https://7tv.app"),
    Triple(R.string.settings_credits_bttv, R.string.settings_credits_bttv_summary, "https://betterttv.com"),
    Triple(R.string.settings_credits_ffz, R.string.settings_credits_ffz_summary, "https://frankerfacez.com"),
    Triple(R.string.settings_credits_recent, R.string.settings_credits_recent_summary, "https://recent-messages.robotty.de"),
)

private const val REPO_URL = "https://github.com/derLesh/Chatter"
private const val SPONSOR_URL = "https://github.com/sponsors/derLesh"
private const val PRIVACY_URL = "https://derlesh.github.io/Chatter/privacy-policy.html"

private val MOBILE_DATA = listOf(
    MobileData.Normal to R.string.mobile_data_normal,
    MobileData.SaveData to R.string.mobile_data_save,
)

/**
 * What Chatter does on mobile data. With Data Saver on, the phone saves data whatever is picked
 * here, which the row says, so that "Normal" is not taken for a promise it cannot keep.
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

private val THEME_MODES = listOf(
    ThemeMode.System to R.string.theme_system,
    ThemeMode.Light to R.string.theme_light,
    ThemeMode.Dark to R.string.theme_dark,
)

/**
 * A choice that lives on one row: what it is, the value it has now, and the options in a sheet
 * behind it. Spelled out as radio buttons, a choice costs a row per option even when the answer
 * is one word — the name colors alone were six rows with a preview each. A choice of two or
 * three short options is better off spelled out, and stays that way.
 *
 * [preview] draws whatever a name cannot say next to an option — and next to the value on the
 * row itself, the way the name colors have to show themselves to be told apart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceItem(
    title: Int,
    value: T,
    options: List<T>,
    label: @Composable (T) -> String,
    onPick: (T) -> Unit,
    hint: Int? = null,
    preview: (@Composable RowScope.(T) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    ListItem(
        headlineContent = { Text(stringResource(title)) },
        // The row carries the same preview as the options do: what a palette does to a name is
        // the thing being chosen, so the closed row has to show it too, not just name it.
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label(value))
                preview?.let {
                    Spacer(Modifier.width(12.dp))
                    it(value)
                }
            }
        },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
        colors = transparentItem(),
        modifier = Modifier.clickable { open = true },
    )
    if (!open) return

    // Picked is done: the sheet slides away by itself rather than waiting to be dismissed.
    val pick = { option: T ->
        onPick(option)
        scope.launch { sheet.hide() }.invokeOnCompletion { if (!sheet.isVisible) open = false }
        Unit
    }
    ModalBottomSheet(
        onDismissRequest = { open = false },
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            hint?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
            }
            options.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { pick(option) }
                        .padding(vertical = 4.dp),
                ) {
                    RadioButton(selected = option == value, onClick = { pick(option) })
                    Text(label(option), style = MaterialTheme.typography.bodyLarge)
                    preview?.let {
                        Spacer(Modifier.width(12.dp))
                        it(option)
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchItem(res: Int, checked: Boolean, onChange: (Boolean) -> Unit, hint: Int? = null) {
    ListItem(
        headlineContent = { Text(stringResource(res)) },
        supportingContent = hint?.let { { Text(stringResource(it)) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = transparentItem(),
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

/** The way into a word list: what it is for, and how many words are on it. */
@Composable
private fun KeywordListItem(title: Int, summary: Int, words: List<String>, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = {
            Text(
                if (words.isEmpty()) stringResource(summary)
                else pluralStringResource(R.plurals.keywords_count, words.size, words.size)
            )
        },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
        colors = transparentItem(),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * A list of words the user builds up one at a time, like the block list: every word is its own
 * row with its own way out, instead of one line of comma-separated text to edit by hand.
 */
@Composable
private fun KeywordsPage(
    words: List<String>,
    emptyText: Int,
    hint: Int,
    addTitle: Int,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }

    SettingsGroup {
        if (words.isEmpty()) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(emptyText)) },
                    supportingContent = { Text(stringResource(hint)) },
                    colors = transparentItem(),
                )
            }
        }
        words.forEach { word ->
            item {
                ListItem(
                    headlineContent = { Text(word) },
                    trailingContent = {
                        IconButton(onClick = { onRemove(word) }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.keyword_remove, word))
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
        Text(stringResource(addTitle))
    }

    if (adding) {
        AddKeywordDialog(
            title = addTitle,
            hint = hint,
            onAdd = onAdd,
            onDismiss = { adding = false },
        )
    }
}

/**
 * The sites whose pictures the app will fetch. Nothing outside this list is ever requested, so
 * the page says what the list is for before it says what is in it.
 */
@Composable
private fun ImageHostsPage(hosts: List<String>, vm: MainViewModel) {
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

@Composable
private fun SliderItem(
    title: String,
    value: Float,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Slider(value = value, onValueChange = onChange, onValueChangeFinished = onDone, valueRange = range, steps = steps)
        },
        colors = transparentItem(),
    )
}

/** The badge providers, in the order their badges appear in front of a name. */
private val BADGE_PROVIDERS = listOf(
    BadgeProvider.Twitch to R.string.settings_provider_twitch,
    BadgeProvider.SevenTv to R.string.settings_provider_seventv,
    BadgeProvider.Chatterino to R.string.settings_provider_chatterino,
    BadgeProvider.Chatter to R.string.settings_provider_chatter,
)

/** The emote providers, in the order their emotes take precedence over each other. */
private val PROVIDERS = listOf(
    EmoteProvider.Twitch to R.string.settings_provider_twitch,
    EmoteProvider.SevenTv to R.string.settings_provider_seventv,
    EmoteProvider.Bttv to R.string.settings_provider_bttv,
    EmoteProvider.Ffz to R.string.settings_provider_ffz,
)

/**
 * What a tap does, on a message or on its name. The hint says what no choice here can change:
 * holding keeps the user card in reach, and with it blocking, reporting and moderating.
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

/** How the time in front of a message is written, each option showing the current time in it. */
@Composable
private fun TimestampPicker(selected: TimestampFormat, onSelect: (TimestampFormat) -> Unit) {
    val now = remember { System.currentTimeMillis() }
    ChoiceItem(
        title = R.string.settings_timestamps,
        value = selected,
        options = TimestampFormat.entries,
        // Every option writes the current time the way it would write it, which says more than
        // "HH:mm" ever could.
        label = { format ->
            format.pattern?.let { SimpleDateFormat(it, Locale.getDefault()).format(Date(now)) }
                ?: stringResource(R.string.settings_timestamps_off)
        },
        onPick = onSelect,
    )
}


/** Choice of launcher icon: black C on white or white C on black. */
@Composable
private fun AppIconPicker() {
    val context = LocalContext.current
    var current by remember { mutableStateOf(AppIcon.current(context)) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_app_icon)) },
        supportingContent = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 4.dp)) {
                    listOf(
                        Triple(AppIcon.Light, Color.White, R.string.app_icon_light),
                        Triple(AppIcon.Dark, Color(0xFF111111), R.string.app_icon_dark),
                    ).forEach { (icon, background, label) ->
                        val selected = icon == current
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    if (!selected) {
                                        AppIcon.set(context, icon)
                                        current = icon
                                    }
                                }
                                .padding(8.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(background)
                                    .border(
                                        if (selected) 3.dp else 1.dp,
                                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        CircleShape,
                                    ),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_chatter_monochrome),
                                    contentDescription = null,
                                    tint = if (background == Color.White) Color(0xFF111111) else Color.White,
                                    modifier = Modifier.size(64.dp),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(label),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        },
        colors = transparentItem(),
    )
}

/** The name colors of a handful of sample chatters, so each palette can be compared at a glance. */
private val NAME_COLOR_LABELS = listOf(
    NameColorPalette.None to R.string.name_colors_none,
    NameColorPalette.Twitch to R.string.name_colors_twitch,
    NameColorPalette.HslLuma to R.string.name_colors_hsl_luma,
    NameColorPalette.HslLoop to R.string.name_colors_hsl_loop,
    NameColorPalette.LuvLuma to R.string.name_colors_luv_luma,
    NameColorPalette.RgbLoop to R.string.name_colors_rgb_loop,
)

// Deliberately hard cases: very dark blue, dark red and dark green are what needs fixing.
private val NAME_COLOR_SAMPLES = listOf(
    0xFF0000FF.toInt(), 0xFF8B0000.toInt(), 0xFF006400.toInt(), 0xFFFF69B4.toInt(), 0xFF00FF7F.toInt(),
)

/** Picks how name colors are adjusted; every option previews the same names in its own palette. */
@Composable
private fun NameColorPicker(selected: NameColorPalette, onSelect: (NameColorPalette) -> Unit) {
    val dark = isAppInDarkTheme()
    ChoiceItem(
        title = R.string.settings_name_colors,
        value = selected,
        options = NAME_COLOR_LABELS.map { it.first },
        label = { palette -> stringResource(NAME_COLOR_LABELS.first { it.first == palette }.second) },
        onPick = onSelect,
        hint = R.string.settings_name_colors_hint,
        // The same five names in every palette: the difference between them is the whole point,
        // and it is not something a name can describe.
        preview = { palette ->
            NAME_COLOR_SAMPLES.forEach { argb ->
                val color = readableNameColor(argb, null, dark, palette)
                Text(
                    text = stringResource(R.string.settings_name_colors_sample),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        },
    )
}


/** Swatches for the mention highlight, plus a preview of a highlighted message. */
@Composable
private fun HighlightColorPicker(selected: Int, onSelect: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val options = listOf(
        Settings.HIGHLIGHT_DEFAULT, Settings.HIGHLIGHT_ACCENT,
        0xFFFF9800.toInt(), 0xFFFFC107.toInt(), 0xFF4CAF50.toInt(), 0xFF00BCD4.toInt(),
        0xFF2196F3.toInt(), 0xFF9C27B0.toInt(), 0xFFE91E63.toInt(),
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_highlight_color)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.settings_highlight_color_hint))
                // A plain scrolling Row: ListItem measures intrinsically, which lazy lists don't support.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()),
                ) {
                    options.forEach { option ->
                        val color = highlightColor(option, scheme)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(color)
                                .then(
                                    if (option == selected) Modifier.border(3.dp, scheme.onSurface, CircleShape) else Modifier
                                )
                                .clickable { onSelect(option) },
                        ) {
                            if (option == selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                                )
                            }
                        }
                    }
                }
                // Preview of a highlighted chat line.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(highlightBackground(selected, scheme))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_highlight_preview_name) + ": ",
                        fontWeight = FontWeight.Bold,
                        color = scheme.primary,
                    )
                    Text(stringResource(R.string.settings_highlight_preview_text), color = scheme.onSurface)
                }
            }
        },
        colors = transparentItem(),
    )
}
