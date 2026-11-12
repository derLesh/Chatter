package me.lesh.chatter.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lesh.chatter.R
import me.lesh.chatter.auth.AuthState
import me.lesh.chatter.debug.AppLog
import me.lesh.chatter.debug.DeviceReport
import me.lesh.chatter.debug.LogReport
import me.lesh.chatter.ui.MainViewModel
import me.lesh.chatter.ui.SettingsSubPage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The hidden category for bug reports, opened by tapping the logo under About five times. */
@Composable
internal fun DebugPage(open: (SettingsSubPage) -> Unit) {
    SettingsGroup {
        item(R.string.debug_log) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.debug_log)) },
                supportingContent = { Text(stringResource(R.string.debug_log_summary)) },
                trailingContent = { NavChevron() },
                colors = transparentItem(),
                modifier = Modifier.tileClickable { open(SettingsSubPage.Log) },
            )
        }
    }
}

/**
 * What the app has done since it started, with the phone on top. Shows exactly what a copy, a
 * saved file or a bug report would carry, so nothing leaves the phone unseen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LogPage(vm: MainViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val auth by vm.authState.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val device = remember(auth, channels.size) { DeviceReport.lines(context, accountKind(auth), channels.size) }
    var entries by remember { mutableStateOf(AppLog.entries()) }
    LaunchedEffect(Unit) {
        AppLog.lastNumber.collectLatest {
            entries = AppLog.entries()
            // A busy chat writes many lines a second; the page need not redraw for each.
            delay(REFRESH_MS)
        }
    }
    val fileText = { LogReport.text(device, AppLog.entries()) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = fileText()
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("no output stream")
                }.isSuccess
            }
            toast(context, if (saved) R.string.debug_log_saved else R.string.debug_log_save_failed)
        }
    }
    val clipLabel = stringResource(R.string.debug_log_clip_label)
    val copy = { text: String -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(clipLabel, text))) } }

    Text(
        stringResource(R.string.debug_log_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        Button(onClick = {
            val logs = LogReport.forIssue(device, AppLog.entries())
            // A link holds only so much; the rest goes into the clipboard for pasting.
            if (!logs.whole) {
                copy(fileText())
                toast(context, R.string.debug_log_report_clipboard)
            }
            context.startActivity(Intent(Intent.ACTION_VIEW, bugReportUrl(vm.device, logs.text).toUri()))
        }) { Text(stringResource(R.string.debug_log_report)) }
        OutlinedButton(onClick = { copy(fileText()) }) { Text(stringResource(R.string.debug_log_copy)) }
        OutlinedButton(onClick = { save.launch(fileName()) }) { Text(stringResource(R.string.debug_log_save)) }
        TextButton(onClick = {
            AppLog.clear()
            entries = emptyList()
        }) { Text(stringResource(R.string.debug_log_clear)) }
    }
    SettingsGroup(R.string.debug_log_device) {
        item {
            SelectionContainer {
                Text(
                    LogReport.deviceText(device).trimEnd(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
    SettingsGroup(R.string.debug_log) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (entries.isEmpty()) {
                    Text(stringResource(R.string.debug_log_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    if (entries.size > SHOWN_LINES) {
                        Text(
                            pluralStringResource(R.plurals.debug_log_shown, entries.size, SHOWN_LINES, entries.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Newest first: that is what someone looking at a bug they just saw wants.
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            entries.asReversed().take(SHOWN_LINES).forEach { entry ->
                                key(entry.number) { LogLine(entry) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogLine(entry: AppLog.Entry) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val levelColor = when (entry.level) {
        AppLog.Level.Error -> MaterialTheme.colorScheme.error
        AppLog.Level.Warn -> MaterialTheme.colorScheme.tertiary
        AppLog.Level.Info -> MaterialTheme.colorScheme.primary
        AppLog.Level.Debug -> muted
    }
    val text = remember(entry, muted, levelColor) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = muted)) { append("${shortTime(entry.at)} ") }
            withStyle(SpanStyle(color = levelColor)) { append("${entry.level.letter} ${entry.tag}") }
            append(" ${entry.message}")
        }
    }
    Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}

/** How the app is used, without whose account it is. */
private fun accountKind(auth: AuthState): String = when (auth) {
    is AuthState.LoggedIn -> "logged in"
    AuthState.Guest -> "guest"
    AuthState.LoggedOut -> "logged out"
    AuthState.Loading -> "loading"
}

/** The phone's local time; the copied text says UTC instead, so reports from anywhere line up. */
private fun shortTime(at: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))

private fun fileName(): String = "chatter-log-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(Date()) + ".txt"

private fun toast(context: Context, text: Int) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

/** Drawing every one of [AppLog.KEPT] lines at once would make the page slow to open. */
private const val SHOWN_LINES = 300

private const val REFRESH_MS = 1_000L
