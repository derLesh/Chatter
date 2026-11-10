package me.lesh.chatter.ui.settings

import android.text.format.Formatter
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.lesh.chatter.R
import me.lesh.chatter.settings.MobileData
import me.lesh.chatter.settings.Settings
import me.lesh.chatter.stats.Stats
import me.lesh.chatter.storage.StorageReport
import me.lesh.chatter.ui.MainViewModel
import kotlinx.coroutines.launch

/** What Chatter downloads and what it keeps on the phone; the cache can be cleared here. */
@Composable
internal fun StoragePage(settings: Settings, vm: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val bytesToday = remember(stats) { stats.trafficOver(1) }
    val bytesWeek = remember(stats) { stats.trafficOver(Stats.KEEP_DAYS) }
    var report by remember { mutableStateOf<StorageReport?>(null) }
    var clearing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { report = vm.storageReport() }
    val size = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }

    SettingsGroup(R.string.settings_stats_group_data) {
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_stats_data_open)) },
                supportingContent = {
                    Text(stringResource(R.string.settings_stats_today_week, size(bytesToday.open), size(bytesWeek.open)))
                },
                colors = transparentItem(),
            )
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_stats_data_background)) },
                supportingContent = {
                    Text(stringResource(R.string.settings_stats_today_week, size(bytesToday.background), size(bytesWeek.background)))
                },
                colors = transparentItem(),
            )
        }
        item(R.string.settings_mobile_data) { MobileDataPicker(settings.mobileData, vm) }
        item(R.string.settings_close_with_app) {
            SwitchItem(R.string.settings_close_with_app, settings.closeWithApp, vm::setCloseWithApp, R.string.settings_close_with_app_hint)
        }
    }
    SettingsGroup(R.string.storage_group) {
        item { SizeRow(R.string.storage_images, R.string.storage_images_hint, report?.images?.let(size)) }
        item { SizeRow(R.string.storage_emote_lists, R.string.storage_emote_lists_hint, report?.emoteLists?.let(size)) }
        item { SizeRow(R.string.storage_saved, R.string.storage_saved_hint, report?.saved?.let(size)) }
        item { SizeRow(R.string.storage_app, R.string.storage_app_hint, report?.app?.let(size)) }
        item(R.string.storage_clear_cache) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.storage_clear_cache)) },
                supportingContent = { Text(stringResource(R.string.storage_clear_cache_hint)) },
                trailingContent = {
                    OutlinedButton(
                        enabled = !clearing,
                        onClick = {
                            clearing = true
                            scope.launch {
                                vm.clearCache()
                                report = vm.storageReport()
                                clearing = false
                            }
                        },
                    ) { Text(stringResource(R.string.storage_clear)) }
                },
                colors = transparentItem(),
            )
        }
    }
}

/** [value] is null while measuring, or where Android did not say. */
@Composable
private fun SizeRow(title: Int, hint: Int, value: String?) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(hint)) },
        trailingContent = { Text(value ?: "–", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium) },
        colors = transparentItem(),
    )
}

internal val MOBILE_DATA = listOf(
    MobileData.Normal to R.string.mobile_data_normal,
    MobileData.SaveData to R.string.mobile_data_save,
)

/**
 * Behaviour on mobile data. The row says when Data Saver is on, since the phone saves data then
 * regardless of the choice here.
 */
@Composable
private fun MobileDataPicker(selected: MobileData, vm: MainViewModel) {
    val saving by vm.saveData.collectAsStateWithLifecycle()
    ChoiceItem(
        title = R.string.settings_mobile_data,
        value = selected,
        options = MOBILE_DATA.map { it.first },
        label = { choice ->
            val name = stringResource(MOBILE_DATA.first { it.first == choice }.second)
            if (saving && choice == MobileData.Normal) stringResource(R.string.mobile_data_saver_on, name) else name
        },
        onPick = vm::setMobileData,
        hint = R.string.settings_mobile_data_hint,
    )
}
