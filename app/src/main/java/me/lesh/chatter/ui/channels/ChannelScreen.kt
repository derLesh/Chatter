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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import kotlinx.coroutines.launch
import me.lesh.chatter.R
import me.lesh.chatter.channels.ChannelAbout
import me.lesh.chatter.channels.ChannelGroup
import me.lesh.chatter.channels.ChannelInfo
import me.lesh.chatter.channels.displayName
import me.lesh.chatter.chat.PostedIn
import me.lesh.chatter.chat.SharedItems
import me.lesh.chatter.ui.IconTab
import me.lesh.chatter.ui.IconTabs
import me.lesh.chatter.ui.MainViewModel
import me.lesh.chatter.ui.rememberPredictiveTransition
import me.lesh.chatter.ui.slideBetweenScreens
import me.lesh.chatter.ui.theme.LiveRed
import java.text.NumberFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Media tiles beside each other on the channel page; the rest are a tap away. */
private const val MEDIA_PREVIEW = 4
private const val LINKS_PREVIEW = 3

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
    val changeable by vm.changeableChannels.collectAsStateWithLifecycle()
    val sevenTvLoggedIn by vm.sevenTvLoggedIn.collectAsStateWithLifecycle()
    val group = groups[page]
    val owner = group == null && page in changeable
    LaunchedEffect(page) { if (group == null) vm.checkChannelSettings(page) }

    // Counts saves, so the page loads anew from what Twitch now says.
    var loads by remember(page) { mutableIntStateOf(0) }
    val about by produceState<ChannelAbout?>(null, page, loads) { if (!ChannelGroup.isKey(page)) value = vm.channelAbout(page) }
    val shared by produceState<SharedItems?>(null, page) { value = vm.sharedIn(page) }
    val settings by produceState<ChannelSettingsState?>(null, page, loads, owner) {
        value = if (owner) ChannelSettingsState(vm.loadChannelSettings(page)) else null
    }
    var tab by rememberSaveable(page) { mutableIntStateOf(0) }
    var saving by remember(page) { mutableStateOf(false) }
    var allOpen by rememberSaveable(page) { mutableStateOf(false) }
    var startOnLinks by rememberSaveable(page) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val channelOf: (PostedIn) -> ChannelInfo? = if (group != null) ({ info[it.channel] ?: ChannelInfo(it.channel) }) else ({ null })

    rememberPredictiveTransition(
        current = allOpen,
        backTo = if (allOpen) false else null,
        onBack = { allOpen = false },
        label = "channel page",
    ).AnimatedContent(transitionSpec = { slideBetweenScreens(forward = targetState) }) { all ->
        if (all) {
            MediaAndLinksScreen(
                shared = shared ?: SharedItems.EMPTY,
                startOnLinks = startOnLinks,
                channelOf = channelOf,
                imageLoader = vm.staticImageLoader,
                onHold = onJump,
                onBack = { allOpen = false },
            )
            return@AnimatedContent
        }
        val uriHandler = LocalUriHandler.current
        val settingsShown = owner && tab == 1
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    },
                    actions = {
                        if (group == null) IconButton(onClick = { uriHandler.openUri("https://www.twitch.tv/$page") }) {
                            Icon(painterResource(R.drawable.ic_open_in_new), stringResource(R.string.action_open_on_twitch))
                        }
                    },
                )
            },
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
                                val job = vm.saveChannelSettings(page, form.change)
                                scope.launch {
                                    job.join()
                                    saving = false
                                    loads++
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
                    owner -> {
                        item(key = "header") { ChannelHeader(page, info[page], about, vm.imageLoader, compact = true) }
                        item(key = "tabs") {
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
                    else -> item(key = "header") { ChannelHeader(page, info[page], about, vm.imageLoader, compact = false) }
                }
                if (settingsShown) {
                    channelSettingsItems(settings, sevenTvLoggedIn, vm.imageLoader, vm::searchCategories)
                } else {
                    if (group == null) aboutItems(about)
                    sharedItems(
                        shared = shared,
                        channelOf = channelOf,
                        imageLoader = vm.staticImageLoader,
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
 * The channel's picture, name and what kind of streamer they are, then whether it is live, its
 * title and category. [compact] puts the picture beside the name, above the owner's tabs.
 */
@Composable
private fun ChannelHeader(login: String, info: ChannelInfo?, about: ChannelAbout?, imageLoader: ImageLoader, compact: Boolean) {
    val name = info?.displayName ?: login
    val facts = listOfNotNull(
        when (about?.broadcasterType) {
            "partner" -> stringResource(R.string.channel_partner)
            "affiliate" -> stringResource(R.string.channel_affiliate)
            else -> null
        },
        about?.followers?.let { pluralStringResource(R.plurals.channel_followers, it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), formatViewers(it.toInt())) },
    ).joinToString(" · ")
    val title = info?.title?.takeIf { info.isLive && it.isNotEmpty() } ?: about?.title.orEmpty()
    val category = about?.category
    val categoryName = category?.name ?: info?.game?.takeIf { info.isLive }.orEmpty()

    if (compact) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ChannelAvatar(info, imageLoader, 60.dp)
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (facts.isNotEmpty()) Text(facts, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (info?.isLive == true) LiveLine(info.viewers, Modifier.padding(top = 4.dp))
                }
            }
            if (title.isNotEmpty()) Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
            if (categoryName.isNotEmpty()) CategoryChip(categoryName, category?.boxArtUrl, imageLoader, Modifier.padding(top = 8.dp))
        }
        return
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
    ) {
        ChannelAvatar(info, imageLoader, 96.dp)
        Text(name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
        if (facts.isNotEmpty()) {
            Text(facts, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        if (info?.isLive == true) LiveLine(info.viewers, Modifier.padding(top = 12.dp))
        if (title.isNotEmpty()) {
            Text(title, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        }
        if (categoryName.isNotEmpty()) CategoryChip(categoryName, category?.boxArtUrl, imageLoader, Modifier.padding(top = 10.dp))
    }
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

/** A combined chat's pictures overlapping, and its name. */
@Composable
private fun GroupHeader(group: ChannelGroup, info: Map<String, ChannelInfo>, imageLoader: ImageLoader) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        GroupAvatar(group.channels, info, imageLoader, 96.dp)
        Text(group.displayName(info), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
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
                shown.forEach { MediaTile(it, channelOf(it.postedIn), imageLoader, onHold, Modifier.weight(1f)) }
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
