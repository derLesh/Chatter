package me.lesh.chatter.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import me.lesh.chatter.emotes.EmoteOwner
import me.lesh.chatter.emotes.TwitchEmoteGroup
import me.lesh.chatter.emotes.groupTwitchEmotes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import me.lesh.chatter.R
import me.lesh.chatter.emotes.Emoji
import me.lesh.chatter.emotes.EmojiGroup
import me.lesh.chatter.emotes.Emote
import me.lesh.chatter.emotes.EmoteProvider
import kotlinx.coroutines.launch

/** What the picker offers: an emote with its image, or an emoji drawn by the system font. */
private sealed interface PickerItem {
    val key: String

    class EmoteItem(val emote: Emote) : PickerItem {
        override val key get() = "e:" + emote.provider.name + emote.id + emote.name
    }

    class EmojiItem(val emoji: Emoji) : PickerItem {
        override val key get() = "j:" + emoji.value
    }
}

/** What a section is called. [Channel] and [Twitch] also give the side column its picture. */
private sealed interface SectionTitle {
    class Text(val res: Int) : SectionTitle
    class Channel(val owner: EmoteOwner) : SectionTitle
    data object Twitch : SectionTitle
}

/** A run of the grid, under a [title] if it has one. */
private class PickerSection(val key: String, val title: SectionTitle?, val items: List<PickerItem>)

/**
 * [reloadable]: its emotes come from lists that can be loaded again; emoji do not.
 * [sideColumn]: a column on the left jumps to each section.
 */
private class PickerTab(
    val title: Int,
    val sections: List<PickerSection>,
    val reloadable: Boolean = true,
    val sideColumn: Boolean = false,
) {
    val isEmpty: Boolean get() = sections.all { it.items.isEmpty() }

    /** Grid index of each section's first entry: its title, or its first item without one. */
    val sectionStarts: List<Int> =
        sections.runningFold(0) { at, s -> at + (if (s.title != null) 1 else 0) + s.items.size }.dropLast(1)

    companion object {
        /** A tab without headers. */
        fun plain(title: Int, items: List<PickerItem>) = PickerTab(title, listOf(PickerSection("all", null, items)))
    }
}

/**
 * Bottom sheet with tabs: recently used, then one per provider with channel emotes first, then
 * emoji in their groups. Twitch emotes are grouped by channel, Twitch's own last, with a column to
 * jump between them; [owners] names the channels. Below the emotes, [onReload] loads them again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmotePickerSheet(
    emotes: List<Emote>,
    owners: Map<String, EmoteOwner>,
    emoji: List<Emoji>,
    recent: List<String>,
    imageLoader: ImageLoader,
    onPick: (Emote) -> Unit,
    onPickEmoji: (Emoji) -> Unit,
    onReload: suspend () -> Unit,
    onDismiss: () -> Unit,
) {
    val tabs = remember(emotes, owners, emoji, recent) {
        val byName = emotes.associateBy { it.name }
        val emojiByValue = emoji.associateBy { it.value }
        val sorted = compareByDescending<Emote> { it.isChannel }.thenBy { it.name.lowercase() }
        fun provider(p: EmoteProvider) = emotes.filter { it.provider == p }.sortedWith(sorted).map { PickerItem.EmoteItem(it) }
        listOf(
            PickerTab.plain(
                R.string.emotes_recent,
                recent.mapNotNull { name ->
                    byName[name]?.let { PickerItem.EmoteItem(it) } ?: emojiByValue[name]?.let { PickerItem.EmojiItem(it) }
                },
            ),
            twitchTab(emotes.filter { it.provider == EmoteProvider.Twitch }, owners),
            PickerTab.plain(R.string.emotes_7tv, provider(EmoteProvider.SevenTv)),
            PickerTab.plain(R.string.emotes_bttv, provider(EmoteProvider.Bttv)),
            PickerTab.plain(R.string.emotes_ffz, provider(EmoteProvider.Ffz)),
            PickerTab(
                R.string.emoji,
                emoji.groupBy { it.group }.map { (group, list) ->
                    PickerSection("g:" + group.name, SectionTitle.Text(group.title), list.map { PickerItem.EmojiItem(it) })
                },
                reloadable = false,
            ),
        )
    }
    var selected by rememberSaveable { mutableIntStateOf(if (recent.isEmpty()) 1 else 0) }
    var reloading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val reload: () -> Unit = {
        scope.launch {
            reloading = true
            try {
                onReload()
            } finally {
                reloading = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.height(420.dp)) {
            // Fixed: a scrolling row hid the last tabs with nothing to show they were there. The
            // smaller label fits six, and transparent takes the sheet's color.
            PrimaryTabRow(selectedTabIndex = selected, containerColor = Color.Transparent) {
                tabs.forEachIndexed { i, tab ->
                    Tab(
                        selected = selected == i,
                        onClick = { selected = i },
                        text = {
                            Text(
                                stringResource(tab.title),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
            val tab = tabs[selected]
            if (tab.isEmpty) {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.emotes_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (tab.reloadable) ReloadButton(reloading, reload)
                }
            } else {
                // Keyed by tab, so switching tabs starts at the top instead of keeping the offset.
                key(selected) {
                    val reloadButton: @Composable () -> Unit = { ReloadButton(reloading, reload) }
                    if (tab.sideColumn) {
                        // Channel names arrive after the emotes. The grid would keep the section it
                        // showed and end up far down, so new sections start it at the top again.
                        key(tab.sections.map { it.key }) {
                            GridWithSideColumn(tab, imageLoader, onPick, onPickEmoji, reloadButton)
                        }
                    } else {
                        PickerGrid(tab, rememberLazyGridState(), imageLoader, onPick, onPickEmoji, reloadButton)
                    }
                }
            }
        }
    }
}

/** One section per channel by name, then Twitch's own; a single group needs no headers. */
private fun twitchTab(emotes: List<Emote>, owners: Map<String, EmoteOwner>): PickerTab {
    val sections = groupTwitchEmotes(emotes, owners).map { group ->
        val items = group.emotes.map { PickerItem.EmoteItem(it) }
        when (group) {
            is TwitchEmoteGroup.Channel -> PickerSection("c:" + group.owner.id, SectionTitle.Channel(group.owner), items)
            is TwitchEmoteGroup.OtherChannels -> PickerSection("other", SectionTitle.Text(R.string.emotes_other_channels), items)
            is TwitchEmoteGroup.Twitch -> PickerSection("twitch", SectionTitle.Twitch, items)
        }
    }
    return if (sections.size > 1) PickerTab(R.string.emotes_twitch, sections, sideColumn = true)
    else PickerTab.plain(R.string.emotes_twitch, sections.flatMap { it.items })
}

@Composable
private fun PickerGrid(
    tab: PickerTab,
    state: LazyGridState,
    imageLoader: ImageLoader,
    onPick: (Emote) -> Unit,
    onPickEmoji: (Emoji) -> Unit,
    reloadButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(52.dp),
        state = state,
        contentPadding = PaddingValues(8.dp),
        modifier = modifier,
    ) {
        tab.sections.forEach { section ->
            section.title?.let { title ->
                item(key = "h:" + section.key, span = { GridItemSpan(maxLineSpan) }) { SectionHeader(title) }
            }
            pickerItems(section.items, imageLoader, onPick, onPickEmoji)
        }
        if (tab.reloadable) item(key = "reload", span = { GridItemSpan(maxLineSpan) }) {
            Box(contentAlignment = Alignment.Center) { reloadButton() }
        }
    }
}

/**
 * The grid with a column of its sections on the left. The column marks the section being read and
 * scrolls the grid to the one tapped.
 */
@Composable
private fun GridWithSideColumn(
    tab: PickerTab,
    imageLoader: ImageLoader,
    onPick: (Emote) -> Unit,
    onPickEmoji: (Emoji) -> Unit,
    reloadButton: @Composable () -> Unit,
) {
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val readSection by remember(tab) {
        derivedStateOf {
            // A short last section never reaches the top, so the end of the grid counts as it.
            if (!grid.canScrollForward && grid.canScrollBackward) tab.sections.lastIndex
            else tab.sectionStarts.indexOfLast { it <= grid.firstVisibleItemIndex }.coerceAtLeast(0)
        }
    }
    // Short sections at the end share one scroll position, so the tapped one stays marked until
    // the user scrolls by hand.
    var tappedSection by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(grid) {
        grid.interactionSource.interactions.collect { if (it is DragInteraction.Start) tappedSection = null }
    }
    Row(Modifier.fillMaxSize()) {
        SectionColumn(tab.sections, tappedSection ?: readSection, imageLoader) { i ->
            tappedSection = i
            scope.launch { grid.animateScrollToItem(tab.sectionStarts[i]) }
        }
        VerticalDivider()
        PickerGrid(tab, grid, imageLoader, onPick, onPickEmoji, reloadButton, Modifier.weight(1f))
    }
}

@Composable
private fun SectionColumn(
    sections: List<PickerSection>,
    current: Int,
    imageLoader: ImageLoader,
    onSelect: (Int) -> Unit,
) {
    val state = rememberLazyListState()
    LaunchedEffect(current) {
        val info = state.layoutInfo
        val shown = info.visibleItemsInfo.any { it.index == current && it.offset >= 0 && it.offset + it.size <= info.viewportEndOffset }
        if (!shown) state.animateScrollToItem(current)
    }
    val firstNotSubscribed = sections.indexOfFirst { (it.title as? SectionTitle.Channel)?.owner?.subscribed != true }
    LazyColumn(
        state = state,
        contentPadding = PaddingValues(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(60.dp).fillMaxHeight(),
    ) {
        itemsIndexed(sections, key = { _, s -> s.key }) { i, section ->
            val selected = i == current
            val label = section.title?.label().orEmpty()
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Sets the subscriptions apart from channels whose follower or bits emotes the user has.
                if (i == firstNotSubscribed && i > 0) HorizontalDivider(Modifier.width(32.dp).padding(vertical = 6.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable { onSelect(i) }
                        .semantics {
                            contentDescription = label
                            this.selected = selected
                        },
                ) {
                    SectionIcon(section, selected, imageLoader)
                }
            }
        }
    }
}

/** A channel's avatar, the Twitch logo, or the first emote where there is neither. */
@Composable
private fun SectionIcon(section: PickerSection, selected: Boolean, imageLoader: ImageLoader) {
    val title = section.title
    if (title is SectionTitle.Twitch) {
        Icon(
            painterResource(R.drawable.ic_site_twitch),
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        return
    }
    val avatar = (title as? SectionTitle.Channel)?.owner?.avatarUrl
    if (avatar != null) {
        AsyncImage(
            model = avatar,
            contentDescription = null,
            imageLoader = imageLoader,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
    } else {
        AsyncImage(
            model = (section.items.firstOrNull() as? PickerItem.EmoteItem)?.emote?.url,
            contentDescription = null,
            imageLoader = imageLoader,
            modifier = Modifier.size(32.dp),
        )
    }
}

/** The title with a line after it, so the end of one section and the start of the next show. */
@Composable
private fun SectionHeader(title: SectionTitle) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Text(
            title.label(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        HorizontalDivider(Modifier.padding(start = 12.dp).weight(1f))
    }
}

@Composable
private fun SectionTitle.label(): String = when (this) {
    is SectionTitle.Text -> stringResource(res)
    is SectionTitle.Channel -> owner.name
    SectionTitle.Twitch -> stringResource(R.string.emotes_twitch)
}

/** Small and grey: needed when an emote is missing, not every time the picker opens. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ReloadButton(reloading: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = !reloading,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        if (reloading) LoadingIndicator(Modifier.size(24.dp))
        else Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.reload_emotes), style = MaterialTheme.typography.labelMedium)
    }
}

private fun LazyGridScope.pickerItems(
    items: List<PickerItem>,
    imageLoader: ImageLoader,
    onPick: (Emote) -> Unit,
    onPickEmoji: (Emoji) -> Unit,
) {
    items(items, key = { it.key }) { item ->
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(52.dp)
                .clickable {
                    when (item) {
                        is PickerItem.EmoteItem -> onPick(item.emote)
                        is PickerItem.EmojiItem -> onPickEmoji(item.emoji)
                    }
                }
                .padding(6.dp),
        ) {
            when (item) {
                is PickerItem.EmoteItem -> AsyncImage(
                    model = item.emote.url,
                    contentDescription = item.emote.name,
                    imageLoader = imageLoader,
                    modifier = Modifier.size(40.dp),
                )
                is PickerItem.EmojiItem -> Text(
                    item.emoji.value,
                    fontSize = 28.sp,
                    modifier = Modifier.semantics { contentDescription = ":" + item.emoji.shortcodes.first() + ":" },
                )
            }
        }
    }
}

private val EmojiGroup.title: Int
    get() = when (this) {
        EmojiGroup.Smileys -> R.string.emoji_smileys
        EmojiGroup.People -> R.string.emoji_people
        EmojiGroup.Animals -> R.string.emoji_animals
        EmojiGroup.Food -> R.string.emoji_food
        EmojiGroup.Travel -> R.string.emoji_travel
        EmojiGroup.Activities -> R.string.emoji_activities
        EmojiGroup.Objects -> R.string.emoji_objects
        EmojiGroup.Symbols -> R.string.emoji_symbols
        EmojiGroup.Flags -> R.string.emoji_flags
    }
