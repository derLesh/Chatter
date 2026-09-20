package dev.chatter.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chatter.app.BuildConfig
import dev.chatter.app.R
import dev.chatter.app.auth.AuthState
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.MessageKind
import dev.chatter.app.chat.Segment
import dev.chatter.app.settings.Settings
import dev.chatter.app.settings.ThemeMode
import dev.chatter.app.ui.channels.ManageChannelsPage
import dev.chatter.app.ui.channels.RenameChannelDialog
import dev.chatter.app.ui.channels.AddChannelDialog
import dev.chatter.app.ui.chat.ChatStyle
import dev.chatter.app.ui.chat.MessageRow
import dev.chatter.app.ui.theme.highlightBackground
import dev.chatter.app.ui.theme.isAppInDarkTheme
import dev.chatter.app.util.AppIcon
import dev.chatter.app.ui.theme.highlightColor
import androidx.compose.material3.RadioButton
import dev.chatter.app.settings.TimestampFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dev.chatter.app.emotes.EmoteProvider
import kotlin.math.roundToInt

/** Top level of the settings, like the Android settings app: categories that open a page. */
private enum class SettingsPage(val title: Int, val summary: Int, val icon: ImageVector) {
    Appearance(R.string.settings_appearance, R.string.settings_appearance_summary, Icons.Default.Edit),
    Chat(R.string.settings_chat, R.string.settings_chat_summary, Icons.AutoMirrored.Filled.List),
    Notifications(R.string.settings_notifications, R.string.settings_notifications_summary, Icons.Default.Notifications),
    Channels(R.string.settings_channels, R.string.settings_channels_summary, Icons.Default.Person),
    Account(R.string.settings_account, R.string.settings_account_summary, Icons.Default.AccountCircle),
    About(R.string.settings_about, R.string.settings_about_summary, Icons.Default.Info),
}

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    val goBack = { if (page != null) page = null else onBack() }
    BackHandler(onBack = goBack)

    val settings by vm.settings.collectAsStateWithLifecycle()
    val auth by vm.authState.collectAsStateWithLifecycle()
    val login = (auth as? AuthState.LoggedIn)?.account?.login.orEmpty()

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            // Like Android: the opened page (title bar included) slides in over the list; going
            // back, it slides out on top. Pages are opaque, so nothing shows through.
            val forward = targetState != null
            val transform = if (forward) {
                slideInHorizontally { it } togetherWith
                    (slideOutHorizontally { -it / 4 } + fadeOut(targetAlpha = 0.5f))
            } else {
                (slideInHorizontally { -it / 4 } + fadeIn(initialAlpha = 0.5f)) togetherWith
                    slideOutHorizontally { it }
            }
            transform.apply { targetContentZIndex = if (forward) 1f else -1f }
        },
        label = "settings-page",
    ) { current ->
        // The about page carries its own icon and app name, so it gets no title bar heading.
        val title = if (current == SettingsPage.About) null else current?.title ?: R.string.settings
        SettingsPageScaffold(title = title, onBack = goBack) {
            when (current) {
                null -> Home(login) { page = it }
                SettingsPage.Appearance -> AppearancePage(settings, vm)
                SettingsPage.Chat -> ChatPage(settings, vm)
                SettingsPage.Notifications -> NotificationsPage(settings, vm)
                SettingsPage.Channels -> ChannelsPage(vm, settings)
                SettingsPage.Account -> AccountPage(login) { vm.logout(); onBack() }
                SettingsPage.About -> AboutPage()
            }
        }
    }
}

/** One settings page: its own collapsing large title bar and scrolling content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPageScaffold(title: Int?, onBack: () -> Unit, content: @Composable () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val back = @Composable {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            // Without a heading a large bar would just be empty space, so it shrinks to a plain one.
            if (title == null) {
                TopAppBar(title = {}, navigationIcon = back)
            } else {
                LargeTopAppBar(
                    title = { Text(stringResource(title)) },
                    navigationIcon = back,
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
private fun Home(login: String, open: (SettingsPage) -> Unit) {
    SettingsGroup {
        SettingsPage.entries.forEach { p ->
            item {
                ListItem(
                    headlineContent = { Text(stringResource(p.title), fontWeight = FontWeight.Medium) },
                    supportingContent = {
                        Text(if (p == SettingsPage.Account && login.isNotEmpty()) login else stringResource(p.summary))
                    },
                    leadingContent = { CategoryIcon(p.icon) },
                    colors = transparentItem(),
                    modifier = Modifier.clickable { open(p) },
                )
            }
        }
    }
}

@Composable
private fun AppearancePage(settings: Settings, vm: MainViewModel) {
    SettingsGroup(R.string.settings_group_colors) {
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_theme)) },
                supportingContent = {
                    val modes = listOf(
                        ThemeMode.System to R.string.theme_system,
                        ThemeMode.Light to R.string.theme_light,
                        ThemeMode.Dark to R.string.theme_dark,
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        modes.forEachIndexed { i, (mode, label) ->
                            SegmentedButton(
                                selected = settings.themeMode == mode,
                                onClick = { vm.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                            ) { Text(stringResource(label)) }
                        }
                    }
                },
                colors = transparentItem(),
            )
        }
        item { SwitchItem(R.string.settings_dynamic_color, settings.dynamicColor, vm::setDynamicColor, R.string.settings_dynamic_color_hint) }
        item { HighlightColorPicker(settings.highlightColor, vm::setHighlightColor) }
        item { AppIconPicker() }
    }

    SettingsGroup(R.string.settings_group_text) {
        item { TextSizeItem(settings, vm) }
    }

    SettingsGroup(R.string.settings_group_messages) {
        item {
            SwitchItem(
                R.string.settings_alternate_background, settings.alternateBackground,
                vm::setAlternateBackground, R.string.settings_alternate_background_hint,
            )
        }
        item {
            SwitchItem(
                R.string.settings_smooth_scrolling, settings.smoothScrolling,
                vm::setSmoothScrolling, R.string.settings_smooth_scrolling_hint,
            )
        }
    }

    SettingsGroup(R.string.settings_group_screen) {
        item {
            SwitchItem(
                R.string.settings_keep_screen_on, settings.keepScreenOn,
                vm::setKeepScreenOn, R.string.settings_keep_screen_on_hint,
            )
        }
    }

    SettingsGroup(R.string.settings_group_emotes) {
        item { SwitchItem(R.string.settings_emotes_enabled, settings.emotesEnabled, vm::setEmotesEnabled, R.string.settings_emotes_new_messages_hint) }
        item { SwitchItem(R.string.settings_animated_emotes, settings.animatedEmotes, vm::setAnimatedEmotes) }
        item { SwitchItem(R.string.settings_zero_width, settings.zeroWidthEmotes, vm::setZeroWidthEmotes, R.string.settings_zero_width_hint) }
        item { SwitchItem(R.string.settings_unlisted_7tv, settings.showUnlisted7tv, vm::setShowUnlisted7tv, R.string.settings_unlisted_7tv_hint) }
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
            segments = listOf(Segment.Text(sampleText)), text = sampleText,
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
        accent = scheme.primary,
        showDeleted = true,
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
                        MessageRow(sample, style, vm.imageLoader, onAction = {})
                    }
                }
            }
        },
        colors = transparentItem(),
    )
}

@Composable
private fun ChatPage(settings: Settings, vm: MainViewModel) {
    SettingsGroup {
        item {
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
        item { SwitchItem(R.string.settings_load_history, settings.loadHistory, vm::setLoadHistory, R.string.settings_load_history_hint) }
        item { SwitchItem(R.string.settings_seventv_events, settings.sevenTvEvents, vm::setSevenTvEvents, R.string.settings_seventv_events_hint) }
        item { SwitchItem(R.string.settings_show_deleted, settings.showDeleted, vm::setShowDeleted, R.string.settings_show_deleted_hint) }
        item { TimestampPicker(settings.timestamps, vm::setTimestamps) }
    }
    SettingsGroup(R.string.settings_emote_providers) {
        PROVIDERS.forEach { (provider, label) ->
            item {
                SwitchItem(label, provider in settings.emoteProviders, { vm.setEmoteProvider(provider, it) })
            }
        }
    }
    SettingsGroup(R.string.settings_suggestions) {
        item { SwitchItem(R.string.settings_emote_suggestions, settings.emoteSuggestions, vm::setEmoteSuggestions, R.string.settings_emote_suggestions_hint) }
        item { SwitchItem(R.string.settings_user_suggestions, settings.userSuggestions, vm::setUserSuggestions, R.string.settings_user_suggestions_hint) }
        item { SwitchItem(R.string.settings_mention_with_at, settings.mentionWithAt, vm::setMentionWithAt, R.string.settings_mention_with_at_hint) }
    }
}

@Composable
private fun NotificationsPage(settings: Settings, vm: MainViewModel) {
    val context = LocalContext.current
    SettingsGroup(R.string.settings_group_mentions) {
        item {
            var keywords by remember(settings.mentionKeywords) { mutableStateOf(settings.mentionKeywords.joinToString(", ")) }
            OutlinedTextField(
                value = keywords,
                onValueChange = { keywords = it },
                label = { Text(stringResource(R.string.settings_keywords)) },
                supportingText = { Text(stringResource(R.string.settings_keywords_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.setMentionKeywords(keywords) }),
                trailingIcon = {
                    TextButton(onClick = { vm.setMentionKeywords(keywords) }) { Text(stringResource(R.string.save)) }
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
        item {
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
}

@Composable
private fun AccountPage(login: String, onLogout: () -> Unit) {
    SettingsGroup {
        item {
            ListItem(
                headlineContent = { Text(login, fontWeight = FontWeight.Medium) },
                supportingContent = { Text(stringResource(R.string.settings_logged_in)) },
                leadingContent = { CategoryIcon(Icons.Default.AccountCircle) },
                colors = transparentItem(),
            )
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.logout)) },
                supportingContent = { Text(stringResource(R.string.settings_logout_hint)) },
                trailingContent = { OutlinedButton(onClick = onLogout) { Text(stringResource(R.string.logout)) } },
                colors = transparentItem(),
            )
        }
    }
}

@Composable
private fun ChannelsPage(vm: MainViewModel, settings: Settings) {
    val channels by vm.channels.collectAsStateWithLifecycle()
    val info by vm.channelInfo.collectAsStateWithLifecycle()
    val customNames by vm.customNames.collectAsStateWithLifecycle()
    val muted by vm.mutedChannels.collectAsStateWithLifecycle()
    val hiddenUnread by vm.hiddenUnread.collectAsStateWithLifecycle()
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    ManageChannelsPage(
        channels = channels,
        info = info,
        muted = muted,
        hiddenUnread = hiddenUnread,
        imageLoader = vm.imageLoader,
        onMove = vm::moveChannel,
        onNotify = vm::setChannelNotify,
        onUnreadVisible = vm::setChannelUnreadVisible,
        onRename = { renameTarget = it },
        onRemove = vm::removeChannel,
        onAdd = { showAdd = true },
    )
    SettingsGroup {
        item {
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
}

@Composable
private fun AboutPage() {
    // App icon (monochrome glyph from the icon pack, tinted with the theme like a themed icon).
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(112.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            Icon(
                painterResource(R.drawable.ic_chatter_monochrome),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(112.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SettingsGroup {
        item { LinkItem(R.string.settings_source_code, R.string.settings_source_code_summary, REPO_URL) }
        item { LinkItem(R.string.settings_report_issue, R.string.settings_report_issue_summary, "$REPO_URL/issues/new") }
    }
    SettingsGroup(R.string.settings_credits) {
        CREDITS.forEach { (title, summary, url) ->
            item { LinkItem(title, summary, url) }
        }
    }
    SettingsGroup(R.string.settings_licenses) {
        DEPENDENCIES.forEach { (name, url) ->
            item { LinkItem(name, stringResource(R.string.license_apache2), url) }
        }
    }
}

/** The libraries Chatter ships, for the license listing. All of them are Apache 2.0. */
private val DEPENDENCIES = listOf(
    "Kotlin" to "https://kotlinlang.org",
    "Kotlin Coroutines" to "https://github.com/Kotlin/kotlinx.coroutines",
    "kotlinx.serialization" to "https://github.com/Kotlin/kotlinx.serialization",
    "AndroidX (Core, Activity, Lifecycle, DataStore)" to "https://developer.android.com/jetpack/androidx",
    "Jetpack Compose" to "https://developer.android.com/jetpack/compose",
    "OkHttp" to "https://square.github.io/okhttp/",
    "Coil" to "https://coil-kt.github.io/coil/",
)

/** The services Chatter builds on, each linking to where it comes from. */
private val CREDITS = listOf(
    Triple(R.string.settings_credits_twitch, R.string.settings_credits_twitch_summary, "https://twitch.tv"),
    Triple(R.string.settings_credits_seventv, R.string.settings_credits_seventv_summary, "https://7tv.app"),
    Triple(R.string.settings_credits_bttv, R.string.settings_credits_bttv_summary, "https://betterttv.com"),
    Triple(R.string.settings_credits_ffz, R.string.settings_credits_ffz_summary, "https://frankerfacez.com"),
    Triple(R.string.settings_credits_recent, R.string.settings_credits_recent_summary, "https://recent-messages.robotty.de"),
)

private const val REPO_URL = "https://github.com/derLesh/Chatter"

@Composable
private fun LinkItem(title: Int, summary: Int, url: String) =
    LinkItem(stringResource(title), stringResource(summary), url)

/** A settings row that hands the link to the browser. */
@Composable
private fun LinkItem(title: String, summary: String, url: String) {
    val context = LocalContext.current
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
        colors = transparentItem(),
        modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
    )
}

// ---- Building blocks ----------------------------------------------------------------------

private class GroupScope {
    val items = mutableListOf<@Composable () -> Unit>()
    fun item(content: @Composable () -> Unit) {
        items += content
    }
}

/**
 * Related settings as separate tiles with a small gap (Android 16 style): the outer corners
 * of the group are strongly rounded, the corners between tiles only slightly.
 */
@Composable
private fun SettingsGroup(title: Int? = null, build: GroupScope.() -> Unit) {
    val items = GroupScope().apply(build).items
    Column {
        if (title != null) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items.forEachIndexed { i, content ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = tileShape(i, items.size),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(Modifier.padding(vertical = 2.dp)) { content() }
                }
            }
        }
    }
}

private fun tileShape(index: Int, count: Int): RoundedCornerShape {
    val outer = 24.dp
    val inner = 4.dp
    return RoundedCornerShape(
        topStart = if (index == 0) outer else inner,
        topEnd = if (index == 0) outer else inner,
        bottomStart = if (index == count - 1) outer else inner,
        bottomEnd = if (index == count - 1) outer else inner,
    )
}

@Composable
private fun CategoryIcon(icon: ImageVector) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun transparentItem() = ListItemDefaults.colors(containerColor = Color.Transparent)

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

/** The emote providers, in the order their emotes take precedence over each other. */
private val PROVIDERS = listOf(
    EmoteProvider.Twitch to R.string.settings_provider_twitch,
    EmoteProvider.SevenTv to R.string.settings_provider_seventv,
    EmoteProvider.Bttv to R.string.settings_provider_bttv,
    EmoteProvider.Ffz to R.string.settings_provider_ffz,
)

/** How the time in front of a message is written, each option showing the current time in it. */
@Composable
private fun TimestampPicker(selected: TimestampFormat, onSelect: (TimestampFormat) -> Unit) {
    val now = remember { System.currentTimeMillis() }
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_timestamps)) },
        supportingContent = {
            Column(Modifier.padding(top = 4.dp)) {
                TimestampFormat.entries.forEach { format ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(format) }
                            .padding(vertical = 2.dp),
                    ) {
                        RadioButton(selected = format == selected, onClick = { onSelect(format) })
                        Text(
                            text = format.pattern?.let { p ->
                                SimpleDateFormat(p, Locale.getDefault()).format(Date(now))
                            } ?: stringResource(R.string.settings_timestamps_off),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        colors = transparentItem(),
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
