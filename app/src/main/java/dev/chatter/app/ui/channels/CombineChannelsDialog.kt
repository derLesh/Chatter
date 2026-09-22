package dev.chatter.app.ui.channels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import dev.chatter.app.R
import dev.chatter.app.channels.ChannelGroup
import dev.chatter.app.channels.ChannelInfo
import dev.chatter.app.channels.displayName

/**
 * Picks the channels of a combined chat and what it is called — a new one, or [group] changed.
 *
 * The name may stay empty; the chat is then called after the channels ticked, which the field
 * shows as its placeholder while it is empty, so it is clear what leaving it empty means.
 */
@Composable
fun CombineChannelsDialog(
    channels: List<String>,
    info: Map<String, ChannelInfo>,
    group: ChannelGroup?,
    imageLoader: ImageLoader,
    onSave: (name: String, channels: Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(group?.name.orEmpty()) }
    var chosen by remember { mutableStateOf(group?.channels.orEmpty().toSet()) }
    val preview = ChannelGroup("", channels = channels.filter { it in chosen }).displayName(info)
    val enough = chosen.size >= 2

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (group == null) R.string.combine_channels else R.string.combined_chat_edit)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.combined_chat_name)) },
                    placeholder = { Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.combined_chat_name_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = stringResource(R.string.combined_chat_pick),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(channels, key = { it }) { login ->
                        val checked = login in chosen
                        val toggle = { chosen = if (checked) chosen - login else chosen + login }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = toggle)
                                .padding(vertical = 4.dp),
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { toggle() })
                            ChannelAvatar(info[login], imageLoader, 32.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                info[login]?.displayName ?: login,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, chosen); onDismiss() }, enabled = enough) {
                Text(stringResource(if (group == null) R.string.combined_chat_create else R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
