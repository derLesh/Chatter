package dev.chatter.app.ui.update

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.chatter.app.R
import dev.chatter.app.ui.changelog.ReleaseCard
import dev.chatter.app.update.AvailableUpdate

/**
 * That a newer Chatter is out, at the top of the settings. Deliberately a card and not a dialog:
 * it waits there until the user has a moment, and the dot on the settings icon is what says it
 * is there at all.
 */
@Composable
fun UpdateCard(update: AvailableUpdate, installed: String, onWhatsNew: () -> Unit) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_download), contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        stringResource(R.string.update_available, update.version),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.update_installed, installed),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                TextButton(onClick = onWhatsNew) { Text(stringResource(R.string.update_whats_new)) }
                Button(onClick = { download(context, update) }) { Text(stringResource(R.string.update_download)) }
            }
        }
    }
}

/** What the new version changes, and the way to it. Opened from [UpdateCard]. */
@Composable
fun UpdatePage(update: AvailableUpdate) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { download(context, update) }, modifier = Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.update_download_version, update.version))
        }
        // Somebody who has never sideloaded an update may well wonder what installing it does to
        // what they have set up.
        Text(
            stringResource(R.string.update_download_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
    update.release()?.let { ReleaseCard(it) }
}

/**
 * Hands the APK to the browser, which downloads it and offers to install it. The app itself
 * installs nothing, so it needs no permission to install packages.
 */
private fun download(context: Context, update: AvailableUpdate) {
    context.startActivity(Intent(Intent.ACTION_VIEW, update.url.toUri()))
}
