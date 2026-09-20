package dev.chatter.app.ui.changelog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chatter.app.BuildConfig
import dev.chatter.app.R
import dev.chatter.app.ui.MainViewModel

/**
 * Greets an update with what it brought. It puts itself on screen only when there is something
 * unread, which the repository works out — a fix release never gets in the way, and a fresh
 * install has no news to catch up on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateNotesSheet(vm: MainViewModel) {
    val releases by vm.unreadReleases.collectAsStateWithLifecycle()
    if (releases.isEmpty()) return

    // Swiping it away counts as having read it, the same as the button does.
    ModalBottomSheet(
        onDismissRequest = vm::markChangelogRead,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.changelog_whats_new),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.changelog_updated_to, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            releases.forEach { ReleaseCard(it) }
            Button(
                onClick = vm::markChangelogRead,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) { Text(stringResource(R.string.changelog_got_it)) }
            Spacer(Modifier.height(16.dp))
        }
    }
}
