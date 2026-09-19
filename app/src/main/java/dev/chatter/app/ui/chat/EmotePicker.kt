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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import dev.chatter.app.R
import dev.chatter.app.emotes.Emote
import dev.chatter.app.emotes.EmoteProvider

/** Bottom sheet with tabs: recently used, then one tab per provider (channel emotes first). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmotePickerSheet(
    emotes: List<Emote>,
    recent: List<String>,
    imageLoader: ImageLoader,
    onPick: (Emote) -> Unit,
    onDismiss: () -> Unit,
) {
    val tabs = remember(emotes, recent) {
        val byName = emotes.associateBy { it.name }
        val sorted = compareByDescending<Emote> { it.isChannel }.thenBy { it.name.lowercase() }
        listOf(
            R.string.emotes_recent to recent.mapNotNull { byName[it] },
            R.string.emotes_twitch to emotes.filter { it.provider == EmoteProvider.Twitch }.sortedWith(sorted),
            R.string.emotes_7tv to emotes.filter { it.provider == EmoteProvider.SevenTv }.sortedWith(sorted),
            R.string.emotes_bttv to emotes.filter { it.provider == EmoteProvider.Bttv }.sortedWith(sorted),
            R.string.emotes_ffz to emotes.filter { it.provider == EmoteProvider.Ffz }.sortedWith(sorted),
        )
    }
    var selected by rememberSaveable { mutableIntStateOf(if (recent.isEmpty()) 1 else 0) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.height(420.dp)) {
            PrimaryScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, (title, list) ->
                    Tab(
                        selected = selected == i,
                        onClick = { selected = i },
                        text = { Text("${stringResource(title)} (${list.size})") },
                    )
                }
            }
            val list = tabs[selected].second
            if (list.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.emotes_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(52.dp),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    items(list, key = { it.provider.name + it.id + it.name }) { emote ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(52.dp)
                                .clickable { onPick(emote) }
                                .padding(6.dp),
                        ) {
                            AsyncImage(
                                model = emote.url,
                                contentDescription = emote.name,
                                imageLoader = imageLoader,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
