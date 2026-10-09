package dev.chatter.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
import dev.chatter.app.R
import dev.chatter.app.emotes.Emoji
import dev.chatter.app.emotes.EmojiGroup
import dev.chatter.app.emotes.Emote
import dev.chatter.app.emotes.EmoteProvider

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

/** A run of the grid, under a [header] if it has one. */
private class PickerSection(val header: Int?, val items: List<PickerItem>)

private class PickerTab(val title: Int, val sections: List<PickerSection>) {
    val isEmpty: Boolean get() = sections.all { it.items.isEmpty() }

    companion object {
        /** A tab without headers. */
        fun plain(title: Int, items: List<PickerItem>) = PickerTab(title, listOf(PickerSection(null, items)))
    }
}

/**
 * Bottom sheet with tabs: recently used, then one per provider with channel emotes first, then
 * emoji in their groups.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmotePickerSheet(
    emotes: List<Emote>,
    emoji: List<Emoji>,
    recent: List<String>,
    imageLoader: ImageLoader,
    onPick: (Emote) -> Unit,
    onPickEmoji: (Emoji) -> Unit,
    onDismiss: () -> Unit,
) {
    val tabs = remember(emotes, emoji, recent) {
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
            PickerTab.plain(R.string.emotes_twitch, provider(EmoteProvider.Twitch)),
            PickerTab.plain(R.string.emotes_7tv, provider(EmoteProvider.SevenTv)),
            PickerTab.plain(R.string.emotes_bttv, provider(EmoteProvider.Bttv)),
            PickerTab.plain(R.string.emotes_ffz, provider(EmoteProvider.Ffz)),
            PickerTab(
                R.string.emoji,
                emoji.groupBy { it.group }.map { (group, list) -> PickerSection(group.title, list.map { PickerItem.EmojiItem(it) }) },
            ),
        )
    }
    var selected by rememberSaveable { mutableIntStateOf(if (recent.isEmpty()) 1 else 0) }

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
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.emotes_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                // Keyed by tab, so switching tabs starts at the top instead of keeping the offset.
                key(selected) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(52.dp),
                        contentPadding = PaddingValues(8.dp),
                    ) {
                        tab.sections.forEach { section ->
                            section.header?.let { header ->
                                item(key = "h:$header", span = { GridItemSpan(maxLineSpan) }) {
                                    Text(
                                        stringResource(header),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
                                    )
                                }
                            }
                            pickerItems(section.items, imageLoader, onPick, onPickEmoji)
                        }
                    }
                }
            }
        }
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
