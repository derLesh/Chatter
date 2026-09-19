package dev.chatter.app.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chatter.app.R
import dev.chatter.app.auth.AuthState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val settings by vm.settings.collectAsStateWithLifecycle()
    val auth by vm.authState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionTitle(R.string.settings_chat)

            var fontSize by remember(settings.fontSize) { mutableFloatStateOf(settings.fontSize) }
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_font_size, fontSize.roundToInt())) },
                supportingContent = {
                    Slider(
                        value = fontSize,
                        onValueChange = { fontSize = it },
                        onValueChangeFinished = { vm.setFontSize(fontSize.roundToInt().toFloat()) },
                        valueRange = 10f..24f,
                        steps = 13,
                    )
                },
            )

            var limit by remember(settings.messageLimit) { mutableFloatStateOf(settings.messageLimit.toFloat()) }
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_message_limit, limit.roundToInt())) },
                supportingContent = {
                    Slider(
                        value = limit,
                        onValueChange = { limit = it },
                        onValueChangeFinished = { vm.setMessageLimit(limit.roundToInt()) },
                        valueRange = 100f..2000f,
                        steps = 18,
                    )
                },
            )

            SwitchItem(R.string.settings_timestamps, settings.showTimestamps, vm::setShowTimestamps)
            SwitchItem(R.string.settings_animated_emotes, settings.animatedEmotes, vm::setAnimatedEmotes)

            HorizontalDivider()
            SectionTitle(R.string.settings_mentions)

            var keywords by remember(settings.mentionKeywords) { mutableStateOf(settings.mentionKeywords.joinToString(", ")) }
            OutlinedTextField(
                value = keywords,
                onValueChange = { keywords = it },
                label = { Text(stringResource(R.string.settings_keywords)) },
                supportingText = { Text(stringResource(R.string.settings_keywords_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.setMentionKeywords(keywords) }),
                trailingIcon = {
                    TextButton(onClick = { vm.setMentionKeywords(keywords) }) { Text(stringResource(R.string.save)) }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_notifications)) },
                supportingContent = { Text(stringResource(R.string.settings_notifications_hint)) },
                modifier = Modifier.padding(top = 8.dp),
                trailingContent = {
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }) { Text(stringResource(R.string.open)) }
                },
            )

            HorizontalDivider()
            SectionTitle(R.string.settings_account)
            val login = (auth as? AuthState.LoggedIn)?.account?.login
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_logged_in_as, login.orEmpty())) },
                trailingContent = {
                    OutlinedButton(onClick = { vm.logout(); onBack() }) { Text(stringResource(R.string.logout)) }
                },
            )
        }
    }
}

@Composable
private fun SectionTitle(res: Int) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchItem(res: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(res)) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = checked, onCheckedChange = onChange)
            }
        },
    )
}
