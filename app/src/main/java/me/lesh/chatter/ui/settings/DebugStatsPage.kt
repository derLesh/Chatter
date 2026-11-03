package me.lesh.chatter.ui.settings

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.lesh.chatter.R
import me.lesh.chatter.debug.DebugSection
import me.lesh.chatter.ui.MainViewModel

/** What the running app looks like from inside, refreshed while open. Only in debug builds. */
@Composable
internal fun DebugStatsPage(vm: MainViewModel) {
    var sections by remember { mutableStateOf<List<DebugSection>>(emptyList()) }
    // Bumped to take a new snapshot at once, e.g. right after a garbage collection.
    var refreshes by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshes) {
        while (true) {
            sections = vm.debugStats()
            delay(REFRESH_MS)
        }
    }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val resources = LocalResources.current

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
        OutlinedButton(
            onClick = {
                val text = asText(sections) { resources.getString(it) }
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(text, text))) }
            },
        ) { Text(stringResource(R.string.debug_copy)) }
        OutlinedButton(
            onClick = {
                scope.launch {
                    vm.collectGarbage()
                    refreshes++
                }
            },
        ) { Text(stringResource(R.string.debug_gc_run)) }
    }
    sections.forEach { section ->
        SettingsGroup(section.title) {
            item {
                SelectionContainer {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        section.rows.forEach { row ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            ) {
                                Text(
                                    stringResource(row.label),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    row.value,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The whole page as plain text, for pasting into an issue. */
private fun asText(sections: List<DebugSection>, string: (Int) -> String) = buildString {
    sections.forEach { section ->
        appendLine("## ${string(section.title)}")
        section.rows.forEach { appendLine("${string(it.label)}: ${it.value}") }
        appendLine()
    }
}.trimEnd()

private const val REFRESH_MS = 2_000L
