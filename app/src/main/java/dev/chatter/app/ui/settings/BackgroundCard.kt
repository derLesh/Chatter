package dev.chatter.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.chatter.app.R
import dev.chatter.app.service.BackgroundStop
import dev.chatter.app.service.BatteryRestrictions
import dev.chatter.app.service.StopReason
import java.text.DateFormat
import java.util.Date

/**
 * Tells the user that Android keeps mentions from arriving: restricted battery use, or the
 * background connection was ended from outside since they last looked. Shown at the top of the
 * notification settings. Only the system settings can change it, so it links there.
 */
@Composable
fun BackgroundCard(stop: BackgroundStop?, battery: BatteryRestrictions, onDismiss: () -> Unit) {
    if (!battery.restricted && stop == null) return
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(end = 8.dp)) {
                    Text(
                        stringResource(if (battery.restricted) R.string.background_restricted_title else R.string.background_stopped_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (battery.restricted) {
                        Text(stringResource(R.string.background_restricted_text), style = MaterialTheme.typography.bodyMedium)
                    }
                    stop?.let {
                        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.at))
                        Text(
                            stringResource(R.string.background_stopped_text, date, stringResource(reasonText(it.reason))),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        // Force stops are the user's own doing, not a battery setting.
                        if (!battery.restricted && battery.optimized && it.reason != StopReason.UserRequest) {
                            Text(stringResource(R.string.background_unrestricted_hint), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                if (stop != null) TextButton(onClick = onDismiss) { Text(stringResource(R.string.background_dismiss)) }
                TextButton(onClick = { open(context, dontKillMyAppUrl()) }) { Text(stringResource(R.string.background_why)) }
                Button(onClick = { openAppSettings(context) }) { Text(stringResource(R.string.background_battery_settings)) }
            }
        }
    }
}

private fun reasonText(reason: StopReason) = when (reason) {
    StopReason.LowMemory -> R.string.background_reason_memory
    StopReason.ResourceUse -> R.string.background_reason_resources
    StopReason.UserRequest -> R.string.background_reason_user
    StopReason.System -> R.string.background_reason_system
}

/**
 * The app's page in the system settings. There is no public intent for its battery page, which is
 * one tap from here.
 */
private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        )
    }
}

private fun open(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}

/**
 * dontkillmyapp.com has a page per vendor with the steps for its settings; unknown vendors get the
 * front page.
 */
private fun dontKillMyAppUrl(): String {
    val maker = Build.MANUFACTURER.lowercase().trim()
    return if (maker in DONT_KILL_MY_APP_PAGES) "https://dontkillmyapp.com/$maker" else "https://dontkillmyapp.com/"
}

private val DONT_KILL_MY_APP_PAGES = setOf(
    "samsung", "xiaomi", "oneplus", "huawei", "oppo", "vivo", "realme", "meizu", "asus", "sony",
    "nokia", "google", "motorola", "lenovo", "wiko", "tecno", "blackview", "unihertz", "htc",
)
