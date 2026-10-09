package dev.chatter.app.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chatter.app.R
import dev.chatter.app.stats.Stats
import dev.chatter.app.ui.MainViewModel
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/** The user's chat stats, counted on this device and never sent; can be reset at the bottom. */
@Composable
internal fun StatsPage(vm: MainViewModel, onChannels: () -> Unit) {
    val context = LocalContext.current
    val stats by vm.stats.collectAsStateWithLifecycle()
    val info by vm.channelInfo.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }
    val busiest = remember(stats) { stats.busiestChannels.take(5) }
    val week = remember(stats) { stats.backgroundByChannel(Stats.KEEP_DAYS) }
    val today = remember(stats) { stats.backgroundByChannel(1).toMap() }
    val outliers = remember(week) { Stats.outliers(week) }
    val bytesToday = remember(stats) { stats.trafficOver(1) }
    val bytesWeek = remember(stats) { stats.trafficOver(Stats.KEEP_DAYS) }
    val size = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }

    SettingsGroup(R.string.settings_stats_group_messages) {
        item { StatRow(R.string.settings_stats_sent, formatNumber(stats.sent)) }
        item { StatRow(R.string.settings_stats_received, formatNumber(stats.received)) }
        item { StatRow(R.string.settings_stats_mentions, formatNumber(stats.mentions)) }
    }
    SettingsGroup(R.string.settings_stats_group_days) {
        item {
            StatRow(
                R.string.settings_stats_active_days,
                pluralStringResource(R.plurals.stats_days, stats.activeDays.size, stats.activeDays.size),
            )
        }
        item {
            val streak = remember(stats) { stats.streak() }
            StatRow(
                R.string.settings_stats_streak,
                pluralStringResource(R.plurals.stats_days, streak, streak),
            )
        }
        if (stats.since > 0) {
            item { StatRow(R.string.settings_stats_since, formatDay(stats.since)) }
        }
    }
    SettingsGroup(R.string.settings_stats_group_channels) {
        if (busiest.isEmpty()) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_stats_no_channels)) },
                    colors = transparentItem(),
                )
            }
        }
        busiest.forEach { (login, count) ->
            item {
                ListItem(
                    headlineContent = { Text(info[login]?.displayName ?: login) },
                    trailingContent = { Text(formatNumber(count), fontWeight = FontWeight.Medium) },
                    colors = transparentItem(),
                )
            }
        }
    }
    // What staying joined costs while the app is closed, per channel, so the user can decide
    // whether to keep a very busy one overnight.
    SettingsGroup(R.string.settings_stats_group_background) {
        if (week.isEmpty()) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_stats_background_none)) },
                    colors = transparentItem(),
                )
            }
        }
        week.take(BACKGROUND_CHANNELS_SHOWN).forEach { (login, count) ->
            item {
                val busy = login in outliers
                ListItem(
                    headlineContent = { Text(info[login]?.displayName ?: login) },
                    supportingContent = {
                        Column {
                            Text(
                                stringResource(
                                    R.string.settings_stats_today_week,
                                    formatNumber(today[login] ?: 0), formatNumber(count),
                                )
                            )
                            if (busy) {
                                Text(
                                    stringResource(R.string.settings_stats_background_busy),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    trailingContent = if (busy) {
                        { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) }
                    } else null,
                    colors = transparentItem(),
                    modifier = if (busy) Modifier.clickable(onClick = onChannels) else Modifier,
                )
            }
        }
    }
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
    }
    SettingsGroup {
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_stats_reset)) },
                supportingContent = { Text(stringResource(R.string.settings_stats_reset_hint)) },
                trailingContent = {
                    OutlinedButton(onClick = { confirmReset = true }) {
                        Text(stringResource(R.string.settings_stats_reset_action))
                    }
                },
                colors = transparentItem(),
            )
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.settings_stats_reset)) },
            text = { Text(stringResource(R.string.settings_stats_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; vm.resetStats() }) {
                    Text(stringResource(R.string.settings_stats_reset_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun StatRow(label: Int, value: String) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        trailingContent = { Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium) },
        colors = transparentItem(),
    )
}

/** How many channels the background figures list; the rest cost little. */
private const val BACKGROUND_CHANNELS_SHOWN = 8

/** Grouped per the phone's locale, so large numbers stay readable. */
private fun formatNumber(n: Long): String = NumberFormat.getIntegerInstance().format(n)

private fun formatDay(at: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at))
