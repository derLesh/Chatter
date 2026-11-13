package me.lesh.chatter.ui.settings

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import me.lesh.chatter.upload.RecentUpload
import me.lesh.chatter.upload.UploadHost
import me.lesh.chatter.upload.CustomUploader
import me.lesh.chatter.upload.PresetUploader
import me.lesh.chatter.upload.UploadAnswer
import me.lesh.chatter.upload.UploadTarget

/** The rows under "Sending pictures" on the Links & images page. */
internal fun GroupScope.sendingPictures(settings: Settings, vm: MainViewModel, open: (SettingsSubPage) -> Unit) {
    item(R.string.settings_uploader) {
        val setup by vm.uploaderSetup.collectAsStateWithLifecycle()
        val chosen = setup.target(settings.uploader) ?: setup.targets.firstOrNull()
        ListItem(
            leadingContent = { Leading { UploaderIcon(chosen, 32.dp) } },
            headlineContent = { Text(stringResource(R.string.settings_uploader)) },
            supportingContent = { Text(chosen?.name.orEmpty()) },
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

/**
 * Where pictures go: the sites Chatter knows and any number of the user's own. Each row has an
 * info button for what the site does with a picture and, where it takes one, its key.
 */
@Composable
internal fun ImageUploaderPage(settings: Settings, vm: MainViewModel) {
    val setup by vm.uploaderSetup.collectAsStateWithLifecycle()
    val chosen = (setup.target(settings.uploader) ?: setup.targets.firstOrNull())?.id
    var aboutPreset by rememberSaveable { mutableStateOf<PresetUploader?>(null) }
    // The id of the site being edited; "" for a new one.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

    Text(
        stringResource(R.string.settings_uploader_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    )
    SettingsGroup {
        PresetUploader.entries.forEach { preset ->
            item {
                val target = UploadTarget.of(preset, setup.keys[preset.id])
                UploaderRow(
                    target = target,
                    favicon = preset.favicon,
                    name = preset.title,
                    selected = chosen == preset.id,
                    // Imgur without any Client-ID: its sheet is where one is entered.
                    onSelect = { if (target != null) vm.setUploader(target) else aboutPreset = preset },
                    onInfo = { aboutPreset = preset },
                )
            }
        }
        setup.custom.forEach { custom ->
            item {
                val target = UploadTarget.of(custom)
                UploaderRow(
                    target = target,
                    favicon = null,
                    name = custom.host.domain,
                    selected = chosen == custom.id,
                    onSelect = { if (target != null) vm.setUploader(target) else editing = custom.id },
                    onInfo = { editing = custom.id },
                )
            }
        }
    }
    OutlinedButton(onClick = { editing = "" }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.uploader_add))
    }

    aboutPreset?.let { preset ->
        PresetUploaderSheet(preset, setup.keys[preset.id].orEmpty(), onSave = { vm.setUploaderKey(preset, it) }, onDismiss = { aboutPreset = null })
    }
    editing?.let { id ->
        val existing = setup.custom.firstOrNull { it.id == id }
        CustomUploaderSheet(
            existing = existing,
            onSave = { host ->
                val saved = CustomUploader(vm.saveCustomUploader(existing?.id, host), host)
                // A new site is what the user wants to try; an edited one keeps the choice as it was.
                if (existing == null) UploadTarget.of(saved)?.let(vm::setUploader)
            },
            onRemove = existing?.let { { vm.removeCustomUploader(it.id) } },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun UploaderRow(target: UploadTarget?, favicon: Int?, name: String, selected: Boolean, onSelect: () -> Unit, onInfo: () -> Unit) {
    ListItem(
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onSelect)
                UploaderIcon(favicon, name, 28.dp)
            }
        },
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            IconButton(onClick = onInfo) {
                Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.uploader_about, name))
            }
        },
        colors = transparentItem(),
        modifier = Modifier.tileClickable(onClick = onSelect),
    )
}

/** What a site Chatter knows does with a picture, and the field for its key if it takes one. */
@Composable
private fun PresetUploaderSheet(preset: PresetUploader, key: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var entered by rememberSaveable { mutableStateOf(key) }
    val target = UploadTarget.of(preset, entered)
    val siteKey = preset.key
    FormSheet(
        title = preset.title,
        confirmLabel = stringResource(if (siteKey != null) R.string.save else R.string.ok),
        onConfirm = { if (siteKey != null) onSave(entered) },
        onDismiss = onDismiss,
    ) {
        // Asked with a stand-in key, so the address shows before a needed key is entered.
        UploadTarget.of(preset, "key")?.let { item(key = "address") { InfoRow(R.string.uploader_info_address, it.host.url) } }
        item(key = "delete") {
            InfoRow(
                R.string.uploader_info_delete,
                when {
                    target?.canDelete == true -> stringResource(R.string.uploader_delete_possible, preset.deletesAt)
                    siteKey != null -> stringResource(R.string.uploader_delete_with_key, siteKey.label)
                    else -> stringResource(R.string.uploader_delete_none)
                },
            )
        }
        item(key = "key") {
            InfoRow(
                R.string.uploader_info_key,
                when {
                    siteKey == null -> stringResource(R.string.uploader_key_none)
                    siteKey.required -> stringResource(R.string.uploader_key_needed, siteKey.label)
                    else -> stringResource(R.string.uploader_key_optional, siteKey.label)
                },
            )
        }
        if (siteKey != null) {
            item(key = "field") {
                OutlinedTextField(
                    value = entered,
                    onValueChange = { entered = it },
                    label = { Text(siteKey.label) },
                    supportingText = { Text(stringResource(R.string.uploader_key_local)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: Int, value: String) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * A site of the user's own, set up like Chatterino's: address, form field, where the answer has
 * the link and the deletion link, and a header for an API key. [onRemove] is there when editing.
 */
@Composable
private fun CustomUploaderSheet(existing: CustomUploader?, onSave: (UploadHost) -> Unit, onRemove: (() -> Unit)?, onDismiss: () -> Unit) {
    val start = existing?.host ?: UploadHost.EMPTY
    var url by rememberSaveable { mutableStateOf(start.url) }
    var field by rememberSaveable { mutableStateOf(start.field) }
    var link by rememberSaveable { mutableStateOf(start.linkKey) }
    var delete by rememberSaveable { mutableStateOf(start.deleteKey) }
    var header by rememberSaveable { mutableStateOf(start.headers.entries.firstOrNull()?.let { "${it.key}: ${it.value}" }.orEmpty()) }
    val parsedHeader = UploadAnswer.header(header)
    val headerWrong = header.isNotBlank() && parsedHeader == null
    val host = UploadHost(
        url = url.trim(), field = field.trim(), linkKey = link.trim(), deleteKey = delete.trim(),
        headers = parsedHeader?.let { mapOf(it) }.orEmpty(),
    )
    FormSheet(
        // Named like the sites Chatter knows, whose sheets carry their name.
        title = existing?.host?.domain ?: stringResource(R.string.uploader_add),
        confirmLabel = stringResource(R.string.save),
        confirmEnabled = host.isValid && !headerWrong,
        onConfirm = { onSave(host) },
        onDismiss = onDismiss,
        titleAction = onRemove?.let { remove ->
            { finish ->
                IconButton(onClick = { finish(remove) }) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.uploader_remove), tint = MaterialTheme.colorScheme.error)
                }
            }
        },
    ) {
        item(key = "url") { UploaderField(R.string.uploader_url, R.string.uploader_url_hint, url, KeyboardType.Uri) { url = it } }
        item(key = "field") { UploaderField(R.string.uploader_field, R.string.uploader_field_hint, field) { field = it } }
        item(key = "link") { UploaderField(R.string.uploader_link, R.string.uploader_link_hint, link) { link = it } }
        item(key = "delete") { UploaderField(R.string.uploader_delete, R.string.uploader_delete_hint, delete) { delete = it } }
        item(key = "header") {
            UploaderField(
                R.string.uploader_header, R.string.uploader_header_hint, header,
                error = R.string.uploader_header_invalid.takeIf { headerWrong },
            ) { header = it }
        }
    }
}

@Composable
private fun UploaderField(
    label: Int,
    hint: Int,
    value: String,
    keyboard: KeyboardType = KeyboardType.Ascii,
    error: Int? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        placeholder = { Text(stringResource(hint)) },
        supportingText = error?.let { { Text(stringResource(it)) } },
        isError = error != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
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
