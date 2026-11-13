package me.lesh.chatter.ui.settings

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import me.lesh.chatter.R
import me.lesh.chatter.settings.Settings
import me.lesh.chatter.ui.FormSheet
import me.lesh.chatter.ui.MainViewModel
import me.lesh.chatter.ui.SettingsSubPage
import me.lesh.chatter.ui.UploaderIcon
import me.lesh.chatter.ui.uploaderTitle
import me.lesh.chatter.upload.RecentUpload
import me.lesh.chatter.upload.UploadHost
import me.lesh.chatter.upload.Uploader

/** The rows under "Sending pictures" on the Links & images page. */
internal fun GroupScope.sendingPictures(settings: Settings, vm: MainViewModel, open: (SettingsSubPage) -> Unit) {
    item(R.string.settings_uploader) {
        ListItem(
            leadingContent = { Leading { UploaderIcon(settings.uploader, settings.customUploadHost, 32.dp) } },
            headlineContent = { Text(stringResource(R.string.settings_uploader)) },
            supportingContent = { Text(uploaderTitle(settings.uploader, settings.customUploadHost)) },
            trailingContent = { NavChevron() },
            colors = transparentItem(),
            modifier = Modifier.tileClickable { open(SettingsSubPage.ImageUploader) },
        )
    }
    item(R.string.settings_recent_uploads) {
        val uploads by vm.recentUploads.collectAsStateWithLifecycle()
        ListItem(
            leadingContent = if (uploads.isEmpty()) null else ({ Leading { PreviewStack(uploads.take(3), vm) } }),
            headlineContent = { Text(stringResource(R.string.settings_recent_uploads)) },
            supportingContent = {
                Text(
                    if (uploads.isEmpty()) stringResource(R.string.settings_recent_uploads_none)
                    else pluralStringResource(R.plurals.settings_recent_uploads_count, uploads.size, uploads.size),
                )
            },
            trailingContent = { NavChevron() },
            colors = transparentItem(),
            modifier = Modifier.tileClickable { open(SettingsSubPage.RecentUploads) },
        )
    }
}

/** One width for both rows' pictures, so their titles line up. */
@Composable
private fun Leading(content: @Composable () -> Unit) {
    Box(Modifier.width(48.dp), contentAlignment = Alignment.CenterStart) { content() }
}

/** The newest previews, laid over each other. */
@Composable
private fun PreviewStack(uploads: List<RecentUpload>, vm: MainViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
        uploads.forEach { Preview(it, vm, 28) }
    }
}

@Composable
private fun Preview(upload: RecentUpload, vm: MainViewModel, sizeDp: Int) {
    AsyncImage(
        model = vm.uploadPreview(upload),
        contentDescription = null,
        imageLoader = vm.imageLoader,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape((sizeDp / 4).dp)),
    )
}

/** Where pictures go: kappa.lol, catbox.moe, or a site set up here like in Chatterino. */
@Composable
internal fun ImageUploaderPage(settings: Settings, vm: MainViewModel) {
    Text(
        stringResource(R.string.settings_uploader_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    )
    SettingsGroup {
        Uploader.entries.forEach { uploader ->
            item {
                ListItem(
                    leadingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = settings.uploader == uploader, onClick = { vm.setUploader(uploader) })
                            UploaderIcon(uploader, settings.customUploadHost, 28.dp)
                        }
                    },
                    headlineContent = { Text(uploaderTitle(uploader, settings.customUploadHost), maxLines = 1) },
                    colors = transparentItem(),
                    modifier = Modifier.tileClickable { vm.setUploader(uploader) },
                )
            }
        }
    }
    if (settings.uploader == Uploader.Custom) CustomUploaderFields(settings.customUploadHost, vm::setCustomUploadHost)
}

/** The four fields of a site of the user's own, saved as they are typed. */
@Composable
private fun CustomUploaderFields(saved: UploadHost, onChange: (UploadHost) -> Unit) {
    var host by remember { mutableStateOf(saved) }
    val change = { next: UploadHost ->
        host = next
        onChange(next)
    }
    Text(
        stringResource(R.string.uploader_custom),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
    )
    UploaderField(R.string.uploader_url, R.string.uploader_url_hint, host.url, KeyboardType.Uri, isError = !host.isValid && host.url.length > "https://".length) {
        change(host.copy(url = it.trim()))
    }
    UploaderField(R.string.uploader_field, R.string.uploader_field_hint, host.field) { change(host.copy(field = it.trim())) }
    UploaderField(R.string.uploader_link, R.string.uploader_link_hint, host.linkKey) { change(host.copy(linkKey = it.trim())) }
    UploaderField(R.string.uploader_delete, R.string.uploader_delete_hint, host.deleteKey) { change(host.copy(deleteKey = it.trim())) }
}

@Composable
private fun UploaderField(
    label: Int,
    hint: Int,
    value: String,
    keyboard: KeyboardType = KeyboardType.Ascii,
    isError: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        placeholder = { Text(stringResource(hint)) },
        supportingText = if (isError) ({ Text(stringResource(R.string.uploader_url_hint)) }) else null,
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The last pictures uploaded, one row each: preview, link, copy and, where the site allows,
 * delete. A tap opens the picture in the browser.
 */
@Composable
internal fun RecentUploadsPage(vm: MainViewModel) {
    val uploads by vm.recentUploads.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val open = { url: String -> context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }

    if (uploads.isEmpty()) {
        Text(
            stringResource(R.string.uploads_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
    } else {
        SettingsGroup {
            uploads.forEach { upload ->
                item {
                    ListItem(
                        leadingContent = { Preview(upload, vm, 40) },
                        headlineContent = {
                            Text(upload.link.removePrefix("https://"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = {
                                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(upload.link, upload.link))) }
                                }) {
                                    Icon(painterResource(R.drawable.ic_content_copy), contentDescription = stringResource(R.string.upload_copy))
                                }
                                // Kept as room when the site cannot delete, so the copy buttons stay in line.
                                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                    if (upload.deletion != null) {
                                        IconButton(onClick = { deleting = upload.id }) {
                                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.upload_delete), tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        },
                        colors = transparentItem(),
                        modifier = Modifier.tileClickable { open(upload.link) },
                    )
                }
            }
        }
        Text(
            stringResource(R.string.uploads_kept),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    uploads.firstOrNull { it.id == deleting }?.let { upload ->
        val deletion = upload.deletion ?: return@let
        FormSheet(
            title = stringResource(R.string.upload_delete_title),
            confirmLabel = stringResource(if (deletion.inBrowser) R.string.upload_delete_in_browser else R.string.upload_delete),
            destructive = true,
            onConfirm = {
                if (deletion.inBrowser) open(deletion.url)
                vm.deleteUpload(upload)
            },
            onDismiss = { deleting = null },
        ) {
            item(key = "picture") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                    Preview(upload, vm, 48)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        upload.link.removePrefix("https://"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
