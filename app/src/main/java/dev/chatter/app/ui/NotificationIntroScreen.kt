package dev.chatter.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import dev.chatter.app.R

/** Whether Android lets Chatter show notifications at all. */
fun notificationsAllowed(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

/** The system page where the user turns Chatter's notifications on or off. */
fun openNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }
}

/**
 * Shown once after the first login, before Android's own question: what Chatter would notify about,
 * so the permission is not refused out of habit.
 */
@Composable
fun NotificationIntroScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    // Android answers without asking once the user has refused twice; an answer that fast means no
    // dialog was shown, so the system setting is the only way left.
    val askedAt = remember { longArrayOf(0L) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && SystemClock.elapsedRealtime() - askedAt[0] < SILENT_REFUSAL_MS) openNotificationSettings(context)
        onDone()
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).safeDrawingPadding()) {
        // At least the screen's height, so the explanation sits in the middle and the buttons at the
        // bottom; a small screen scrolls instead.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 28.dp, vertical = 32.dp),
        ) {
            Spacer(Modifier.height(0.dp))
            IntroExplanation()
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 28.dp)) {
                Button(
                    onClick = {
                        askedAt[0] = SystemClock.elapsedRealtime()
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.notification_intro_allow)) }
                TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.notification_intro_later))
                }
                Text(
                    stringResource(R.string.notification_intro_settings_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun IntroExplanation() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(88.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        ) {
            Icon(
                Icons.Default.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.notification_intro_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.notification_intro_text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            IntroPoint(Icons.Default.Star, R.string.notification_intro_mentions)
            IntroPoint(Icons.Default.Email, R.string.notification_intro_whispers)
            IntroPoint(Icons.Default.Refresh, R.string.notification_intro_connection)
        }
    }
}

@Composable
private fun IntroPoint(icon: ImageVector, text: Int) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(16.dp))
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Faster than anybody reads a permission dialog. */
private const val SILENT_REFUSAL_MS = 400L
