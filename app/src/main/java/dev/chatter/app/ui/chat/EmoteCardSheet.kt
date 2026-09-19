package dev.chatter.app.ui.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import dev.chatter.app.R
import dev.chatter.app.emotes.Emote
import dev.chatter.app.emotes.EmoteProvider

/**
 * Details of a tapped emote: big image, name, provider, channel/global, author and flags, with
 * actions to insert it, copy its name or open its page. For stacked (zero-width) emotes every
 * layer can be selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmoteCardSheet(
    emotes: List<Emote>,
    imageLoader: ImageLoader,
    onInsert: (Emote) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(emotes) { mutableIntStateOf(0) }
    val emote = emotes[selected.coerceIn(emotes.indices)]
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
        ) {
            // Large image; falls back to the normal size if the provider has no large one.
            var model by remember(emote) { mutableStateOf(emote.largeUrl) }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(128.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                AsyncImage(
                    model = model,
                    contentDescription = emote.name,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Fit,
                    onError = { if (model != emote.url) model = emote.url },
                    modifier = Modifier.size(width = (96 * EmoteSizes.aspectRatio(emote)).coerceAtMost(280f).dp, height = 96.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(emote.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(
                providerName(emote.provider),
                stringResource(if (emote.isChannel) R.string.emote_channel else R.string.emote_global),
                emote.author?.let { stringResource(R.string.emote_by, it) },
            ).joinToString(" \u00B7 ")
            Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            val flags = listOfNotNull(
                stringResource(R.string.emote_zero_width).takeIf { emote.zeroWidth },
                stringResource(R.string.emote_unlisted).takeIf { emote.unlisted },
            )
            if (flags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                    flags.forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            if (emotes.size > 1) {
                Text(
                    stringResource(R.string.emote_stack),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    emotes.forEachIndexed { i, e ->
                        AsyncImage(
                            model = e.url,
                            contentDescription = e.name,
                            imageLoader = imageLoader,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .then(
                                    if (i == selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                                    else Modifier
                                )
                                .clickable { selected = i }
                                .padding(6.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = { onInsert(emote); onDismiss() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.emote_insert), maxLines = 1)
                }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(emote.name)) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.emote_copy_name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                emote.pageUrl?.let { url ->
                    OutlinedButton(
                        onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } catch (e: ActivityNotFoundException) {
                                // No browser: nothing to open.
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.emote_open_page), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

private fun providerName(provider: EmoteProvider) = when (provider) {
    EmoteProvider.Twitch -> "Twitch"
    EmoteProvider.SevenTv -> "7TV"
    EmoteProvider.Bttv -> "BetterTTV"
    EmoteProvider.Ffz -> "FrankerFaceZ"
}
