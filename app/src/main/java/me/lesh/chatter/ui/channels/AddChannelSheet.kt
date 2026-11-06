package me.lesh.chatter.ui.channels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import me.lesh.chatter.R
import me.lesh.chatter.net.HelixChannelSearch
import me.lesh.chatter.ui.FormSheet
import me.lesh.chatter.ui.theme.LiveRed
import kotlinx.coroutines.delay

/** Finds a channel by name as it is typed; a tap on a result or Add takes it. */
@Composable
fun AddChannelSheet(
    search: suspend (String) -> List<HelixChannelSearch>,
    imageLoader: ImageLoader,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<HelixChannelSearch>>(emptyList()) }
    // The query the results belong to; until they arrive, "nothing found" would be a lie.
    var resultsFor by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(query) {
        delay(300) // debounce typing
        results = search(query)
        resultsFor = query
    }

    FormSheet(
        title = stringResource(R.string.add_channel),
        confirmLabel = stringResource(R.string.add),
        onConfirm = { onAdd(query) },
        onDismiss = onDismiss,
        confirmEnabled = query.isNotBlank(),
        focusOnOpen = focus,
    ) { finish ->
        item(key = "field") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(stringResource(R.string.channel_name)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (query.isNotBlank()) finish { onAdd(query) } }),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp).focusRequester(focus),
            )
        }
        if (results.isEmpty() && query.isNotBlank() && resultsFor == query) {
            item(key = "none") {
                Text(
                    stringResource(R.string.add_channel_none_found),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
        items(results, key = { "result:${it.id}" }) { r ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = stringResource(R.string.add_channel_named, r.displayName)) {
                        finish { onAdd(r.login) }
                    }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
            ) {
                AsyncImage(
                    model = r.thumbnailUrl,
                    contentDescription = null,
                    imageLoader = imageLoader,
                    modifier = Modifier.size(40.dp).clip(CircleShape),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    r.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (r.isLive) Text(stringResource(R.string.live), color = LiveRed, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
