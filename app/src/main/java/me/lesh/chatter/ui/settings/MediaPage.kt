package me.lesh.chatter.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.lesh.chatter.R
import me.lesh.chatter.chat.ImageLinks
import me.lesh.chatter.chat.PostSite
import me.lesh.chatter.settings.Settings
import me.lesh.chatter.ui.MainViewModel
import me.lesh.chatter.ui.SettingsSubPage
import me.lesh.chatter.ui.chat.siteName

/** Linked images, GIFs and posts in the chat. */
@Composable
internal fun MediaPage(settings: Settings, vm: MainViewModel, open: (SettingsSubPage) -> Unit) {
    SettingsGroup {
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
        item(R.string.settings_show_gifs) {
            SwitchItem(R.string.settings_show_gifs, settings.showGifs, vm::setShowGifs, R.string.settings_show_gifs_hint)
        }
        item(R.string.settings_posts) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_posts)) },
                supportingContent = {
                    val sites = PostSite.entries.filter { it in settings.postSites }.map { stringResource(siteName(it)) }
                    Text(sites.joinToString(", ").ifEmpty { stringResource(R.string.settings_off) })
                },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                colors = transparentItem(),
                modifier = Modifier.clickable { open(SettingsSubPage.Posts) },
            )
        }
        item(R.string.settings_full_links) { SwitchItem(R.string.settings_full_links, settings.fullLinks, vm::setFullLinks, R.string.settings_full_links_hint) }
    }
}

/**
 * The sites linked images are loaded from. Nothing else is ever requested, so the page explains the
 * list first.
 */
@Composable
internal fun ImageHostsPage(hosts: List<String>, vm: MainViewModel) {
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
