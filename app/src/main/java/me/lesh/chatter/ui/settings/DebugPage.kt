package me.lesh.chatter.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lesh.chatter.R
import me.lesh.chatter.auth.AuthState
import me.lesh.chatter.debug.AppLog
import me.lesh.chatter.debug.DeviceReport
import me.lesh.chatter.debug.LogReport
import me.lesh.chatter.ui.MainViewModel
import me.lesh.chatter.ui.SettingsSubPage
import me.lesh.chatter.ui.chat.ButtonAction
import me.lesh.chatter.ui.chat.ButtonChoice
import me.lesh.chatter.ui.chat.ConnectedButtons
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
 * What the app has done since it started, live, newest on top, with the phone above it. The page
 * shows the private log; reporting, copying and saving each ask whether the full one should go.
 */
@Composable
internal fun LogPage(vm: MainViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val auth by vm.authState.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    // "Running for" would stand still otherwise.
    var minutes by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            minutes++
        }
    }
    val device = remember(auth, channels.size, minutes) { DeviceReport.lines(context, accountKind(auth), channels.size) }
    var entries by remember { mutableStateOf(AppLog.entries().asReversed()) }
    LaunchedEffect(Unit) {
        // A busy chat writes many lines a second; a few redraws a second still read as live.
        AppLog.revision.collect {
            entries = AppLog.entries().asReversed()
            delay(REFRESH_MS)
        }
    }

    val clipLabel = stringResource(R.string.debug_log_clip_label)
    val copy = { text: String -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(clipLabel, text))) } }
    val fileText = { full: Boolean -> LogReport.text(device, AppLog.entries(), full) }
    val report = { full: Boolean ->
        val logs = LogReport.forIssue(device, AppLog.entries(), full)
        // A link holds only so much; the rest goes into the clipboard for pasting.
        if (!logs.whole) {
            copy(fileText(full))
            toast(context, R.string.debug_log_report_clipboard)
        }
        context.startActivity(Intent(Intent.ACTION_VIEW, bugReportUrl(vm.device, logs.text).toUri()))
    }
    // Which log the file picker was opened for; it hands back only where to write.
    var saveFull by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = fileText(saveFull)
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("no output stream")
                }.isSuccess
            }
            toast(context, if (saved) R.string.debug_log_saved else R.string.debug_log_save_failed)
        }
    }
    fun bothLogs(label: Int, onClick: (full: Boolean) -> Unit) = ButtonAction(
        label,
        choices = listOf(
            ButtonChoice(R.string.debug_log_private) { onClick(false) },
            ButtonChoice(R.string.debug_log_full) { onClick(true) },
        ),
    )
    val actions = listOf(
        bothLogs(R.string.debug_log_report) { report(it) },
        bothLogs(R.string.debug_log_copy) { copy(fileText(it)) },
        bothLogs(R.string.debug_log_save) {
            saveFull = it
            save.launch(fileName(it))
        },
    )

    val card = MaterialTheme.colorScheme.surfaceContainer
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        item(key = "actions") { ConnectedButtons(actions, tonal = true) }
        item(key = "device") {
            SelectionContainer {
                Text(
                    LogReport.deviceText(device).trimEnd(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(vertical = 16.dp)
                        .fillMaxWidth()
                        .background(card, RoundedCornerShape(CORNER))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        if (entries.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.debug_log_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().background(card, RoundedCornerShape(CORNER)).padding(16.dp),
                )
            }
        }
        // One card made of rows, so each line can slide in on its own as it arrives.
        items(entries, key = { it.number }) { entry ->
            val first = entry === entries.first()
            val last = entry === entries.last()
            val shape = RoundedCornerShape(
                topStart = if (first) CORNER else 0.dp,
                topEnd = if (first) CORNER else 0.dp,
                bottomStart = if (last) CORNER else 0.dp,
                bottomEnd = if (last) CORNER else 0.dp,
            )
            LogLine(
                entry,
                Modifier
                    .animateItem()
                    .fillMaxWidth()
                    .background(card, shape)
                    .padding(horizontal = 16.dp)
                    .padding(top = if (first) 12.dp else 3.dp, bottom = if (last) 12.dp else 3.dp),
            )
        }
        item(key = "end") { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun LogLine(entry: AppLog.Entry, modifier: Modifier) {
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
            append(" ${entry.private}")
        }
    }
    SelectionContainer(modifier) { Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
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

private fun fileName(full: Boolean): String =
    "chatter-log-" + (if (full) "full-" else "") + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(Date()) + ".txt"

private fun toast(context: Context, text: Int) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

private val CORNER = 24.dp

private const val REFRESH_MS = 250L
