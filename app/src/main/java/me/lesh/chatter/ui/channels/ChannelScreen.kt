package me.lesh.chatter.ui.channels

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.lesh.chatter.R
import me.lesh.chatter.channels.ChannelAbout
import me.lesh.chatter.channels.ChannelGroup
import me.lesh.chatter.channels.ChannelInfo
import me.lesh.chatter.channels.displayName
import me.lesh.chatter.chat.PostSite
import me.lesh.chatter.chat.PostedIn
import me.lesh.chatter.chat.SharedItems
import me.lesh.chatter.settings.postCard
import me.lesh.chatter.ui.IconTab
import me.lesh.chatter.ui.IconTabs
import me.lesh.chatter.ui.MainViewModel
import me.lesh.chatter.ui.rememberPredictiveTransition
import me.lesh.chatter.ui.slideBetweenScreens
import me.lesh.chatter.ui.pill.MessagePillHost
import me.lesh.chatter.ui.pill.PillContent
import me.lesh.chatter.ui.pill.showPill
import me.lesh.chatter.ui.theme.LiveRed
import java.text.NumberFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Media tiles beside each other on the channel page; the rest are a tap away. */
private const val MEDIA_PREVIEW = 4
private const val LINKS_PREVIEW = 3

/** How long the page waits for Twitch before it shows a spinner, as long as the user card waits. */
private const val PAGE_WAIT_MS = 800L

/** How long it waits at most; after that it shows what it has. */
private const val PAGE_GIVE_UP_MS = 5_000L
private val HEADER_AVATAR = 60.dp

/**
 * A channel's page, like a group's in a messenger: what Twitch says about the channel on top, then
 * the media and links its chat shared, as far as the messages are still in memory. [page] is a
 * channel or a combined chat, which lists its channels instead and marks each item with its own.
 *
 * Where the user may change the channel, a Settings tab beside it holds the title, category, tags,
 * language and 7TV set. Holding a picture or link goes to its message through [onJump].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(
    vm: MainViewModel,
    page: String,
    onOpenPage: (String) -> Unit,
    onJump: (PostedIn) -> Unit,
    onBack: () -> Unit,
) {
    val info by vm.channelInfo.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val sevenTvLoggedIn by vm.sevenTvLoggedIn.collectAsStateWithLifecycle()
    val account by vm.activeAccountId.collectAsStateWithLifecycle()
    val powerSave by vm.powerSaveMode.collectAsStateWithLifecycle()
    val saveData by vm.saveData.collectAsStateWithLifecycle()
    val appSettings by vm.settings.collectAsStateWithLifecycle()
    val group = groups[page]
    val tileLook = TileLook(
        stills = PostSite.entries.filterTo(HashSet()) { appSettings.postCard(it).media },
        blurNsfw = appSettings.blurNsfwPosts,
    )

    var about by remember(page) { mutableStateOf<ChannelAbout?>(null) }
    var shared by remember(page) { mutableStateOf<SharedItems?>(null) }
    var settings by remember(page) { mutableStateOf<ChannelSettingsState?>(null) }
    // Parts arriving one by one would make the page jump, so it shows once all are there, in one
    // step. The hold menu started the requests, so most are there already; a slow answer gets a
    // spinner, and one that never comes is given up on.
    var waited by remember(page) { mutableStateOf(false) }
    var slow by remember(page) { mutableStateOf(false) }
    // Another account may change other things; the page asks again for it.
    LaunchedEffect(page, account) {
        val spinner = launch {
            delay(PAGE_WAIT_MS)
            slow = true
        }
        val answer = withTimeoutOrNull(PAGE_GIVE_UP_MS) { if (ChannelGroup.isKey(page)) null to null else vm.channelPage(page) }
        about = answer?.first
        settings = answer?.second?.let(::ChannelSettingsState)
        spinner.cancel()
        waited = true
    }
    // Asked again when Battery Saver or data saving changes, which decide what is a preview.
    LaunchedEffect(page, powerSave, saveData) { shared = vm.sharedIn(page) }
    val owner = settings != null
    var tab by rememberSaveable(page) { mutableIntStateOf(0) }
    var saving by remember(page) { mutableStateOf(false) }
    var allOpen by rememberSaveable(page) { mutableStateOf(false) }
    var startOnLinks by rememberSaveable(page) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    // The chat shows these while it is on screen; here the page does, as for a save.
    LaunchedEffect(resources) {
        vm.messages.collect { message ->
            snackbar.showPill(
                message.fill
                    ?.let { fill -> PillContent.filled(fill, message.tone) { resources.getString(message.text, it) } }
                    ?: PillContent(resources.getString(message.text), message.tone),
            )
        }
    }
    val channelOf: (PostedIn) -> ChannelInfo? = if (group != null) ({ info[it.channel] ?: ChannelInfo(it.channel) }) else ({ null })

    rememberPredictiveTransition(
        current = allOpen,
        backTo = if (allOpen) false else null,
        onBack = { allOpen = false },
        label = "channel page",
    ).AnimatedContent(transitionSpec = { slideBetweenScreens(forward = targetState) }) { all ->
        if (!waited || shared == null) {
            // The bar with the name, so back works while the page waits.
            Column(Modifier.fillMaxSize()) {
                PageBar(onBack, onTwitch = null, title = group?.displayName(info) ?: info[page]?.displayName ?: page)
                if (slow) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(32.dp))
                    }
                }
            }
            return@AnimatedContent
        }
        if (all) {
            MediaAndLinksScreen(
                shared = shared ?: SharedItems.EMPTY,
                startOnLinks = startOnLinks,
                channelOf = channelOf,
                imageLoader = vm.staticImageLoader,
                look = tileLook,
                onHold = onJump,
                onBack = { allOpen = false },
            )
            return@AnimatedContent
        }
        val uriHandler = LocalUriHandler.current
        val settingsShown = owner && tab == 1
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            snackbarHost = { MessagePillHost(snackbar) },
            topBar = { PageBar(onBack, onTwitch = if (group == null) ({ uriHandler.openUri("https://www.twitch.tv/$page") }) else null) },
            bottomBar = {
                val form = settings
                if (settingsShown && form != null) {
                    // On the page's own color, so the list scrolling under it does not show through.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Button(
                            onClick = {
                                saving = true
                                val change = form.change
                                val result = vm.saveChannelSettings(page, change)
                                scope.launch {
                                    // A failed save keeps everything as typed, to try again.
                                    if (result.await()) {
                                        settings = form.saved(change)
                                        about = vm.channelPage(page).first ?: about
                                    }
                                    saving = false
                                }
                            },
                            enabled = form.canSave && !form.searching && !saving,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) { Text(stringResource(R.string.save)) }
                    }
                }
            },
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
                when {
                    group != null -> {
                        item(key = "header") { GroupHeader(group, info, vm.imageLoader) }
                        item(key = "members-heading") { SectionHeading(stringResource(R.string.channel_members)) }
                        items(group.channels, key = { "member:$it" }) { member -> MemberRow(info[member] ?: ChannelInfo(member), vm.imageLoader) { onOpenPage(member) } }
                    }
                    else -> {
                        item(key = "header") { ChannelHeader(page, info[page], about, vm.imageLoader) }
                        if (owner) item(key = "tabs") {
                            IconTabs(
                                listOf(
                                    IconTab(stringResource(R.string.channel_tab_channel), R.drawable.ic_chat),
                                    IconTab(stringResource(R.string.channel_tab_settings), R.drawable.ic_tune),
                                ),
                                selected = tab,
                                onPick = { tab = it },
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
                if (settingsShown) {
                    channelSettingsItems(settings, sevenTvLoggedIn, vm.imageLoader, vm::searchCategories)
                } else {
                    if (group == null) aboutItems(about)
                    sharedItems(
                        shared = shared,
                        channelOf = channelOf,
                        imageLoader = vm.staticImageLoader,
                        look = tileLook,
                        onHold = onJump,
                        onAll = { links ->
                            startOnLinks = links
                            allOpen = true
                        },
                    )
                }
            }
        }
        settings?.takeIf { it.choosingLanguage }?.let { LanguageSheet(it) }
    }
}

/**
 * The channel's picture beside its name, what kind of streamer they are and whether it is live,
 * then its title and category. The same for every channel, with or without the owner's tabs below.
 */
@Composable
private fun ChannelHeader(login: String, info: ChannelInfo?, about: ChannelAbout?, imageLoader: ImageLoader) {
    val name = info?.displayName ?: login
    val facts = listOfNotNull(
        when (about?.broadcasterType) {
            "partner" -> stringResource(R.string.channel_partner)
            "affiliate" -> stringResource(R.string.channel_affiliate)
            else -> null
        },
        about?.followers?.let { pluralStringResource(R.plurals.channel_followers, it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), formatViewers(it.toInt())) },
    ).joinToString(" · ")
    // Title and category from the same answer: the channel's, which is newer than the live poll
    // after a save, or else the poll's.
    val fromAbout = about?.title?.isNotEmpty() == true
    val live = info?.isLive == true
    val title = if (fromAbout) about?.title.orEmpty() else info?.title?.takeIf { live }.orEmpty()
    val category = about?.category?.takeIf { fromAbout }
    val categoryName = if (fromAbout) category?.name.orEmpty() else info?.game?.takeIf { live }.orEmpty()
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ChannelAvatar(info, imageLoader, HEADER_AVATAR)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (facts.isNotEmpty()) Text(facts, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (info?.isLive == true) LiveLine(info.viewers, Modifier.padding(top = 4.dp))
            }
        }
        if (title.isNotEmpty()) Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
        if (categoryName.isNotEmpty()) CategoryChip(categoryName, category?.boxArtUrl, imageLoader, Modifier.padding(top = 8.dp))
    }
}

/** Back, and the channel on Twitch where [onTwitch] is set. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageBar(onBack: () -> Unit, onTwitch: (() -> Unit)?, title: String = "") {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        },
        actions = {
            if (onTwitch != null) IconButton(onClick = onTwitch) {
                Icon(painterResource(R.drawable.ic_open_in_new), stringResource(R.string.action_open_on_twitch))
            }
        },
    )
}

/** "Live · 12,431 watching" on Twitch's red. */
@Composable
private fun LiveLine(viewers: Int, modifier: Modifier = Modifier) {
    Text(
        pluralStringResource(R.plurals.channel_watching, viewers, NumberFormat.getIntegerInstance().format(viewers)),
        style = MaterialTheme.typography.labelLarge,
        color = Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(LiveRed)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun CategoryChip(name: String, cover: String?, imageLoader: ImageLoader, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
    ) {
        CategoryCover(cover, imageLoader, width = 20, height = 27)
        Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A combined chat's pictures overlapping beside its name, like a channel's header. */
@Composable
private fun GroupHeader(group: ChannelGroup, info: Map<String, ChannelInfo>, imageLoader: ImageLoader) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        GroupAvatar(group.channels, info, imageLoader, HEADER_AVATAR)
        Text(group.displayName(info), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** One channel of a combined chat; tapping opens its own page. */
@Composable
private fun MemberRow(channel: ChannelInfo, imageLoader: ImageLoader, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        ChannelAvatar(channel, imageLoader, 40.dp)
        Column(Modifier.weight(1f)) {
            Text(channel.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (channel.isLive) {
                Text(
                    listOf(stringResource(R.string.status_live, formatViewers(channel.viewers)), channel.game).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = LiveRed,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The channel's description, how long it exists, its language and tags. */
private fun LazyListScope.aboutItems(about: ChannelAbout?) {
    if (about == null) return
    if (about.description.isNotBlank()) item(key = "description") {
        var open by rememberSaveable { mutableStateOf(false) }
        Text(
            about.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (open) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 8.dp).padding(top = 8.dp),
        )
    }
    val since = about.createdAt?.let { created ->
        val locale = Locale.getDefault()
        created.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMyyyy"), locale))
    }
    item(key = "facts") {
        val language = about.language.takeIf { it.isNotEmpty() }?.let { languageName(it) }
        val parts = listOfNotNull(since?.let { stringResource(R.string.channel_since, it) }, language)
        if (parts.isNotEmpty()) {
            Text(
                parts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
    if (about.tags.isNotEmpty()) item(key = "tags") {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            about.tags.forEach { tag ->
                Text(
                    tag,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

/** The newest pictures and links of the chat, each heading opening all of them. */
private fun LazyListScope.sharedItems(
    shared: SharedItems?,
    channelOf: (PostedIn) -> ChannelInfo?,
    imageLoader: ImageLoader,
    look: TileLook,
    onHold: (PostedIn) -> Unit,
    onAll: (links: Boolean) -> Unit,
) {
    item(key = "shared-band") { Spacer(Modifier.fillMaxWidth().padding(top = 16.dp).height(8.dp).background(MaterialTheme.colorScheme.surfaceContainerLow)) }
    if (shared == null) {
        item(key = "shared-loading") {
            Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
        }
        return
    }
    item(key = "media-heading") {
        SectionHeading(stringResource(R.string.channel_media), count = shared.media.size.takeIf { it > 0 }) { onAll(false) }
    }
    item(key = "media") {
        if (shared.media.isEmpty()) {
            PageNote(stringResource(R.string.channel_media_none))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                val shown = shared.media.take(MEDIA_PREVIEW)
                shown.forEach { MediaTile(it, channelOf(it.postedIn), imageLoader, look, onHold, Modifier.weight(1f)) }
                // Fewer than a row keep their size instead of stretching.
                repeat(MEDIA_PREVIEW - shown.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    item(key = "links-heading") {
        SectionHeading(stringResource(R.string.channel_links), Modifier.padding(top = 8.dp), count = shared.links.size.takeIf { it > 0 }) { onAll(true) }
    }
    if (shared.links.isEmpty()) {
        item(key = "links-none") { PageNote(stringResource(R.string.channel_links_none)) }
    } else {
        items(shared.links.take(LINKS_PREVIEW), key = { "link:" + it.url }) { LinkRow(it, channelOf(it.postedIn), imageLoader, onHold) }
    }
    item(key = "shared-source") {
        Text(
            stringResource(R.string.channel_shared_source),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        )
    }
}

/** A section's name, and with a [count] a "Show all" that opens [onAll]. */
@Composable
private fun SectionHeading(text: String, modifier: Modifier = Modifier, count: Int? = null, onAll: () -> Unit = {}) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(if (count != null) Modifier.clickable(onClickLabel = stringResource(R.string.channel_show_all, count), onClick = onAll) else Modifier)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        if (count != null) {
            Text(count.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
