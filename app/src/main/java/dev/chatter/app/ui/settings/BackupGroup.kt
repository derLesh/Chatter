package dev.chatter.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chatter.app.R
import dev.chatter.app.settings.BackupCheck
import dev.chatter.app.settings.SettingsBackup
import dev.chatter.app.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Exporting the configuration to a file and importing it, in Chatter's own format. */
@Composable
internal fun BackupGroup(vm: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<Int?>(null) }
    var pending by remember { mutableStateOf<SettingsBackup?>(null) }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME)) { uri ->
        if (uri != null) {
            val text = vm.exportBackup()
            scope.launch {
                status = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                            ?: error("no output stream")
                    }.fold({ R.string.backup_exported }, { R.string.backup_failed })
                }
            }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                // Null if it could not be opened; empty if it is too large to be a backup.
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { BackupCheck.readLimited(it) ?: "" }
                    }.getOrNull()
                }
                if (text == null) {
                    status = R.string.backup_failed
                    return@launch
                }
                // Nothing is written until the user has seen the changes.
                pending = vm.readBackup(text)
                if (pending == null) status = R.string.backup_invalid
            }
        }
    }

    pending?.let { backup ->
        val hosts = backup.settings?.imageHosts
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.backup_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.backup_confirm_text))
                    Text(
                        stringResource(
                            R.string.backup_confirm_counts,
                            backup.rules?.size ?: 0,
                            backup.nicknames?.size ?: 0,
                            backup.channels?.logins?.size ?: 0,
                        ),
                    )
                    // The image hosts are the one backup setting that reaches outside the phone, so
                    // they are listed.
                    if (hosts != null) {
                        Text(
                            if (hosts.isEmpty()) stringResource(R.string.backup_confirm_no_hosts)
                            else stringResource(R.string.backup_confirm_hosts, hosts.joinToString(", ")),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    scope.launch {
                        vm.applyBackup(backup)
                        status = R.string.backup_imported
                    }
                }) { Text(stringResource(R.string.backup_restore)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    SettingsGroup(R.string.settings_group_backup) {
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.backup_export)) },
                supportingContent = { Text(stringResource(R.string.backup_export_hint)) },
                trailingContent = {
                    OutlinedButton(onClick = { export.launch(backupFileName()) }) {
                        Text(stringResource(R.string.backup_save))
                    }
                },
                colors = transparentItem(),
            )
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.backup_import)) },
                supportingContent = { Text(stringResource(R.string.backup_import_hint)) },
                trailingContent = {
                    OutlinedButton(onClick = { import.launch(arrayOf(BACKUP_MIME, "text/plain")) }) {
                        Text(stringResource(R.string.open))
                    }
                },
                colors = transparentItem(),
            )
        }
        status?.let { res ->
            item {
                ListItem(
                    headlineContent = { Text(stringResource(res), color = MaterialTheme.colorScheme.primary) },
                    colors = transparentItem(),
                )
            }
        }
    }
}

private const val BACKUP_MIME = "application/json"

/** "chatter-2026-09-20.json", dated so several backups can coexist. */
private fun backupFileName(): String =
    "chatter-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"
