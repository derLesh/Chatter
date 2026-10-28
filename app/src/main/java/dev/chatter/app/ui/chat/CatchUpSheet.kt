package dev.chatter.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import dev.chatter.app.R
import dev.chatter.app.ai.CatchUp
import dev.chatter.app.ai.ModelTrouble
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.ui.BetaBadge
import dev.chatter.app.ui.CatchUpSummary

/** What arrived while the user was away, summed up by Gemini Nano as it writes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatchUpSheet(summary: CatchUpSummary, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    ImageVector.vectorResource(R.drawable.ic_auto_awesome),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.catch_up_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(10.dp))
                BetaBadge()
            }
            Text(
                pluralStringResource(R.plurals.catch_up_subtitle, summary.count, summary.count, summary.page),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(16.dp))
            val text = remember(summary.text) { CatchUp.tidy(summary.text) }
            when {
                summary.trouble != null -> Text(
                    stringResource(troubleText(summary.trouble)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                text.isEmpty() && !summary.done -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.catch_up_writing), style = MaterialTheme.typography.bodyLarge)
                }
                text.isEmpty() -> Text(stringResource(R.string.catch_up_empty), style = MaterialTheme.typography.bodyLarge)
                else -> Text(text, style = MaterialTheme.typography.bodyLarge)
            }
            if (summary.leftOut.isNotEmpty() && summary.trouble == null) LeftOut(summary.leftOut)
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.catch_up_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The messages Google's filter refused, folded away behind how many there are. */
@Composable
private fun LeftOut(messages: List<ChatItem>) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(top = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { open = !open }
                .padding(vertical = 4.dp),
        ) {
            Text(
                pluralStringResource(R.plurals.catch_up_left_out, messages.size, messages.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open) messages.forEach { item ->
            Text(
                "${item.displayName ?: item.login ?: ""}: ${item.text.ifBlank { item.systemText.orEmpty() }}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp),
            )
        }
    }
}

private fun troubleText(trouble: ModelTrouble) = when (trouble) {
    ModelTrouble.Busy -> R.string.catch_up_busy
    ModelTrouble.Quota -> R.string.catch_up_quota
    ModelTrouble.Unsupported -> R.string.catch_up_unsupported
    ModelTrouble.Refused -> R.string.catch_up_refused
    ModelTrouble.Other -> R.string.catch_up_failed
}
