package dev.chatter.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.chatter.app.R
import dev.chatter.app.ui.MainViewModel

/** What only a developer needs: screens that otherwise show once. Only in debug builds. */
@Composable
internal fun DeveloperPage(vm: MainViewModel) {
    SettingsGroup {
        item(R.string.settings_show_onboarding) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_show_onboarding)) },
                supportingContent = { Text(stringResource(R.string.settings_show_onboarding_hint)) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                colors = transparentItem(),
                modifier = Modifier.clickable(onClick = vm::showOnboardingAgain),
            )
        }
    }
}
