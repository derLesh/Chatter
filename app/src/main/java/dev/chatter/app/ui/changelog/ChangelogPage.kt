package dev.chatter.app.ui.changelog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chatter.app.R
import dev.chatter.app.changelog.Level
import dev.chatter.app.changelog.Release

/** What each level is called for someone reading the changelog, rather than releasing it. */
private val LEVEL_LABELS = mapOf(
    Level.Major to R.string.changelog_major,
    Level.Minor to R.string.changelog_minor,
    Level.Patch to R.string.changelog_patch,
)

/**
 * Every release Chatter has had, newest first, as one card each. Reached from the about page, and
 * the same cards make up the sheet that greets an update.
 */
@Composable
fun ChangelogPage(releases: List<Release>, currentVersion: String) {
    if (releases.isEmpty()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.changelog_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }
        return
    }
    releases.forEach { release ->
        ReleaseCard(release, isCurrent = release.version.toString() == currentVersion)
    }
}

/** One release: which version it was, when it landed, and what it changed. */
@Composable
fun ReleaseCard(release: Release, isCurrent: Boolean = false) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    release.version.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                // Says which of the releases is the one running, so the list has a "you are here".
                if (isCurrent) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.changelog_installed),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (release.date.isNotEmpty()) {
                Text(
                    release.date,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            release.groups.forEach { group ->
                // A group whose entries never named a level has no heading to put above them.
                val label = LEVEL_LABELS[group.level]
                if (label != null) {
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                } else {
                    Spacer(Modifier.height(12.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    group.entries.forEach { entry ->
                        Row {
                            Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text(entry, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
