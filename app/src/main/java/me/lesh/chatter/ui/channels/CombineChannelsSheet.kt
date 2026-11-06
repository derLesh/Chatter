package me.lesh.chatter.ui.channels

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import me.lesh.chatter.R
import me.lesh.chatter.channels.ChannelGroup
import me.lesh.chatter.channels.ChannelInfo
import me.lesh.chatter.channels.displayName
import me.lesh.chatter.ui.FormSheet

/**
 * Picks a combined chat's channels and name, for a new one or for [group]. A new one starts with
 * [preselected] ticked. An empty name falls back to the channels' names, shown as placeholder.
 */
@Composable
fun CombineChannelsSheet(
    channels: List<String>,
    info: Map<String, ChannelInfo>,
    group: ChannelGroup?,
    imageLoader: ImageLoader,
    onSave: (name: String, channels: Set<String>) -> Unit,
    onDismiss: () -> Unit,
    preselected: String? = null,
) {
    var name by rememberSaveable { mutableStateOf(group?.name.orEmpty()) }
    var chosen by rememberSaveable(stateSaver = ChosenSaver) {
        mutableStateOf(group?.channels?.toSet() ?: setOfNotNull(preselected))
    }
    val preview = ChannelGroup("", channels = channels.filter { it in chosen }).displayName(info)

    FormSheet(
        title = stringResource(if (group == null) R.string.combine_channels else R.string.combined_chat_edit),
        confirmLabel = stringResource(if (group == null) R.string.combined_chat_create else R.string.save),
        onConfirm = { onSave(name, chosen) },
        onDismiss = onDismiss,
        confirmEnabled = chosen.size >= 2,
        text = stringResource(R.string.combined_chat_pick),
    ) {
        item(key = "name") {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.combined_chat_name)) },
                placeholder = { Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingText = { Text(stringResource(R.string.combined_chat_name_hint)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            )
        }
        items(channels, key = { "channel:$it" }) { login ->
            val checked = login in chosen
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .toggleable(
                        value = checked,
                        role = Role.Checkbox,
                        onValueChange = { chosen = if (it) chosen + login else chosen - login },
                    ),
            ) {
                Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(horizontal = 12.dp))
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

private val ChosenSaver = Saver<Set<String>, ArrayList<String>>(save = { ArrayList(it) }, restore = { it.toSet() })
