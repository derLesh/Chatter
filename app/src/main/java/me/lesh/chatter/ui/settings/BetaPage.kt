package me.lesh.chatter.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.lesh.chatter.BuildConfig
import me.lesh.chatter.R
import me.lesh.chatter.ai.ModelState
import me.lesh.chatter.settings.Settings
import me.lesh.chatter.ui.BetaBadge
import me.lesh.chatter.ui.MainViewModel

/** Features that are not finished: each is marked as such and can be switched off again. */
@Composable
internal fun BetaPage(settings: Settings, vm: MainViewModel) {
    Text(
        stringResource(R.string.settings_beta_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
    )
    // One group per feature, so a feature's status is not read as part of the next one.
    SettingsGroup {
        item(R.string.settings_catch_up) {
            BetaSwitchItem(R.string.settings_catch_up, settings.catchUpSummary, vm::setCatchUpSummary, R.string.settings_catch_up_hint)
        }
        if (settings.catchUpSummary) item { ModelStateItem(vm) }
    }
    SettingsGroup {
        item(R.string.settings_colorful_categories) {
            BetaSwitchItem(
                R.string.settings_colorful_categories, settings.colorfulCategories,
                vm::setColorfulCategories, R.string.settings_colorful_categories_hint,
            )
        }
    }
    if (BuildConfig.ASSISTANT_ACCESS) SettingsGroup {
        item(R.string.settings_assistant) {
            // AppFunctions came with Android 16.
            val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA
            BetaSwitchItem(
                R.string.settings_assistant,
                settings.assistantActions && supported,
                vm::setAssistantActions,
                if (supported) R.string.settings_assistant_hint else R.string.settings_assistant_unsupported,
                enabled = supported,
            )
        }
    }
}

/** Like [SwitchItem], with the beta mark next to the title. */
@Composable
private fun BetaSwitchItem(res: Int, checked: Boolean, onChange: (Boolean) -> Unit, hint: Int, enabled: Boolean = true) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A long title wraps instead of squeezing the mark into a column of letters.
                Text(stringResource(res), Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                BetaBadge()
            }
        },
        supportingContent = { Text(stringResource(hint)) },
        trailingContent = { SettingsSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        colors = transparentItem(),
        modifier = Modifier.tileClickable(enabled = enabled) { onChange(!checked) },
    )
}

/** Whether Gemini Nano is on the phone, with a way to get it or to ask again. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModelStateItem(vm: MainViewModel) {
    val state by vm.onDeviceModel.collectAsStateWithLifecycle()
    // A system update or a finished download may have changed the answer since the last visit.
    LaunchedEffect(Unit) { vm.prepareOnDeviceModel() }
    ListItem(
        headlineContent = {
            Text(
                stringResource(
                    when (state) {
                        ModelState.Unknown -> R.string.model_state_checking
                        ModelState.Unsupported -> R.string.model_state_unsupported
                        ModelState.Downloadable -> R.string.model_state_downloadable
                        ModelState.Downloading -> R.string.model_state_downloading
                        ModelState.Ready -> R.string.model_state_ready
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        trailingContent = {
            when (state) {
                ModelState.Unknown, ModelState.Downloading -> LoadingIndicator(Modifier.size(32.dp))
                ModelState.Downloadable -> TextButton(onClick = vm::prepareOnDeviceModel) { Text(stringResource(R.string.model_download)) }
                ModelState.Unsupported -> TextButton(onClick = vm::prepareOnDeviceModel) { Text(stringResource(R.string.model_check_again)) }
                ModelState.Ready -> Unit
            }
        },
        colors = transparentItem(),
    )
}
