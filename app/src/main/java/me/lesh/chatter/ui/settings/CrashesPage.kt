package me.lesh.chatter.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.lesh.chatter.R
import me.lesh.chatter.crash.Crash
import me.lesh.chatter.crash.CrashReport
import me.lesh.chatter.crash.DeviceInfo
import me.lesh.chatter.ui.FormSheet
import me.lesh.chatter.ui.MainViewModel
import java.text.DateFormat
import java.util.Date

/**
 * The last crashes, each with the log before it, to post on GitHub, copy or share. Under About,
 * not in the hidden Debug category, so anyone asked for one finds it.
 */
@Composable
internal fun CrashesPage(vm: MainViewModel) {
    LaunchedEffect(Unit) { vm.loadCrashes() }
    val crashes by vm.crashes.collectAsStateWithLifecycle()
    // By time, so the open crash outlasts turning the phone.
    var shownAt by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val report = rememberCrashReport(vm.device)

    val list = crashes ?: return
    val shown = list.firstOrNull { it.at == shownAt }
    SettingsGroup(hint = R.string.crashes_hint) {
        if (list.isEmpty()) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.crashes_none)) },
                    colors = transparentItem(),
                )
            }
        }
        list.forEach { crash ->
            item {
                ListItem(
                    headlineContent = { Text(crash.errorKind, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(crashSummary(crash)) },
                    trailingContent = { NavChevron() },
                    colors = transparentItem(),
                    modifier = Modifier.tileClickable { shownAt = crash.at },
                )
            }
        }
        if (list.isNotEmpty()) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.crashes_clear), color = MaterialTheme.colorScheme.error) },
                    colors = transparentItem(),
                    modifier = Modifier.tileClickable { confirmClear = true },
                )
            }
        }
    }

    shown?.let { crash ->
        CrashSheet(
            crash,
            onReport = { report(crash) },
            onDelete = {
                shownAt = null
                vm.deleteCrash(crash.at)
            },
            onDismiss = { shownAt = null },
        )
    }
    if (confirmClear) {
        FormSheet(
            title = stringResource(R.string.crashes_clear_title),
            confirmLabel = stringResource(R.string.crashes_clear_confirm),
            destructive = true,
            onConfirm = vm::clearCrashes,
            onDismiss = { confirmClear = false },
        )
    }
}

/** "12 Nov 2026, 14:03 · Chatter 0.10.0 (10)", in the phone's own date format. */
@Composable
private fun crashSummary(crash: Crash): String {
    val at = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(crash.at))
    val version = crash.header["Chatter"] ?: return at
    return stringResource(R.string.crash_summary, at, version)
}

/** One crash in full, with what can be done with it. The text can be selected to copy a part. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CrashSheet(crash: Crash, onReport: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val clipLabel = stringResource(R.string.crash_clip_label)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(crash.errorKind, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                crashSummary(crash),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.crash_public_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Button(onClick = onReport) { Text(stringResource(R.string.crash_report)) }
                OutlinedButton(onClick = {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(clipLabel, crash.text))) }
                }) { Text(stringResource(R.string.crash_copy)) }
                OutlinedButton(onClick = { shareCrash(context, crash) }) { Text(stringResource(R.string.crash_share)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.crash_delete), color = MaterialTheme.colorScheme.error) }
            }
            // Scrolls sideways, so the frames keep one line each and stay readable.
            SelectionContainer(Modifier.horizontalScroll(rememberScrollState())) {
                Text(
                    crash.text.trimEnd(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    softWrap = false,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
    }
}

/**
 * Opens a bug report with the crash in its fields. What does not fit into the link goes into the
 * clipboard, and a toast says to paste it.
 */
@Composable
internal fun rememberCrashReport(device: DeviceInfo): (Crash) -> Unit {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val clipLabel = stringResource(R.string.crash_clip_label)
    return { crash ->
        val issue = CrashReport.forIssue(crash)
        if (!issue.whole) {
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(clipLabel, crash.text))) }
            Toast.makeText(context, R.string.crash_report_clipboard, Toast.LENGTH_LONG).show()
        }
        val url = bugReportUrl(device, crash = issue)
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }
}

/** The whole crash as text, to any app the user picks: a chat, a mail, a note. */
private fun shareCrash(context: Context, crash: Crash) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Chatter crash: ${crash.errorKind}")
        putExtra(Intent.EXTRA_TEXT, crash.text)
    }
    context.startActivity(Intent.createChooser(send, null))
}

/**
 * After a crash, the next start asks once whether to look at it, and from there to report it.
 * Shows the crash before anything goes to GitHub. Waits for the notes on an update, so two sheets
 * never stack.
 */
@Composable
fun CrashOfferSheet(vm: MainViewModel) {
    val crash by vm.crashToOffer.collectAsStateWithLifecycle()
    val releases by vm.unreadReleases.collectAsStateWithLifecycle()
    val showing by vm.offeredCrashShown.collectAsStateWithLifecycle()
    val report = rememberCrashReport(vm.device)
    showing?.let { shown ->
        CrashSheet(
            shown,
            onReport = { report(shown) },
            onDelete = {
                vm.showOfferedCrash(null)
                vm.deleteCrash(shown.at)
            },
            onDismiss = { vm.showOfferedCrash(null) },
        )
    }
    val offered = crash ?: return
    if (releases.isNotEmpty()) return
    FormSheet(
        title = stringResource(R.string.crash_offer_title),
        text = stringResource(R.string.crash_offer_text),
        confirmLabel = stringResource(R.string.crash_offer_show),
        onConfirm = { vm.showOfferedCrash(offered) },
        onDismiss = vm::crashOffered,
    )
}
