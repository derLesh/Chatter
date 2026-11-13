package me.lesh.chatter.ui.settings

import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.lesh.chatter.R
import me.lesh.chatter.ui.SettingsSubPage

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
