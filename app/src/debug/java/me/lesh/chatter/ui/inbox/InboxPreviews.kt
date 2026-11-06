package me.lesh.chatter.ui.inbox

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import coil3.ImageLoader
import coil3.asImage
import coil3.intercept.Interceptor
import coil3.request.SuccessResult
import me.lesh.chatter.channels.ChannelInfo
import me.lesh.chatter.chat.InboxMention
import me.lesh.chatter.chat.InboxWhisper
import me.lesh.chatter.settings.ThemeMode
import me.lesh.chatter.ui.theme.ChatterTheme
import me.lesh.chatter.util.INBOX_TAB_MENTIONS
import me.lesh.chatter.util.INBOX_TAB_WHISPERS

/*
 * The inbox with made-up mentions and whispers, to look at without a login. On the phone:
 * adb shell am start -n me.lesh.chatter/androidx.compose.ui.tooling.PreviewActivity \
 *   --es composable me.lesh.chatter.ui.inbox.InboxPreviewsKt.MentionsLight
 */

@Preview @Composable fun MentionsLight() = SampleInbox(dark = false, tab = INBOX_TAB_MENTIONS)
@Preview @Composable fun MentionsDark() = SampleInbox(dark = true, tab = INBOX_TAB_MENTIONS)
@Preview @Composable fun WhispersLight() = SampleInbox(dark = false, tab = INBOX_TAB_WHISPERS)
@Preview @Composable fun WhispersDark() = SampleInbox(dark = true, tab = INBOX_TAB_WHISPERS)
@Preview @Composable fun EmptyLight() = SampleInbox(dark = false, tab = INBOX_TAB_MENTIONS, empty = true)
@Preview @Composable fun ClearLight() = SampleInbox(dark = false, tab = INBOX_TAB_MENTIONS, clearing = true)
@Preview @Composable fun EmptyDark() = SampleInbox(dark = true, tab = INBOX_TAB_WHISPERS, empty = true)

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

@Composable
private fun SampleInbox(dark: Boolean, tab: Int, empty: Boolean = false, clearing: Boolean = false) {
    val now = remember { System.currentTimeMillis() }
    val mentions = if (empty) emptyList() else sampleMentions(now)
    val whispers = if (empty) emptyList() else sampleWhispers(now)
    ChatterTheme(themeMode = if (dark) ThemeMode.Dark else ThemeMode.Light, dynamicColor = false) {
        InboxContent(
            pager = rememberPagerState(initialPage = tab) { 2 },
            mentions = mentions,
            whispers = whispers,
            mentionUnread = mentions.count { !it.read },
            whisperUnread = whispers.count { !it.read },
            info = SampleChannels,
            nameOf = { _, displayName -> displayName },
            ownLogin = "lesh",
            imageLoader = sampleImageLoader(),
            snackbar = remember { SnackbarHostState() },
            onBack = {},
            onOpenMention = {},
            onOpenWhisper = {},
            onMarkAllRead = {},
            onClear = {},
        )
        if (clearing) ClearInboxSheet(tab, onConfirm = {}, onDismiss = {})
    }
}

private val SampleChannels = listOf(
    ChannelInfo("nordlicht_tv", displayName = "Nordlicht_TV", avatarUrl = "sample:#3F7DE8", isLive = true),
    ChannelInfo("pixelmara", displayName = "PixelMara", avatarUrl = "sample:#E8613F"),
    ChannelInfo("kaffeekatze", displayName = "KaffeeKatze", avatarUrl = "sample:#2FA36B", isLive = true),
    ChannelInfo("speedrunsandra", displayName = "SpeedrunSandra", avatarUrl = "sample:#9B4FD6"),
).associateBy { it.login }

private fun sampleMentions(now: Long) = listOf(
    InboxMention("m1", "nordlicht_tv", "flo_zockt", "Flo_zockt", "@lesh kommst du heute Abend noch in den Raid? Wir starten um 20 Uhr", now - 2 * MINUTE),
    InboxMention("m2", "kaffeekatze", "mila", "Mila", "lesh danke für den Tipp mit den Einstellungen, läuft jetzt viel flüssiger", now - 18 * MINUTE),
    InboxMention("m3", "nordlicht_tv", "bytebaron", "ByteBaron", "@lesh hast du den Clip von gestern gesehen? Der Sprung am Ende war unglaublich", now - 47 * MINUTE, read = true),
    InboxMention("m4", "pixelmara", "pixelmara", "PixelMara", "Shoutout an @lesh für die Hilfe beim Overlay heute!", now - 3 * HOUR, read = true),
    InboxMention("m5", "speedrunsandra", "tobi_rennt", "Tobi_rennt", "lesh welchen Split benutzt du für Welt 4? Ich verliere da immer Zeit", now - 26 * HOUR, read = true),
    InboxMention("m6", "kaffeekatze", "kaffeekatze", "KaffeeKatze", "@lesh willkommen zurück! Lange nicht gesehen", now - 30 * HOUR, read = true),
    InboxMention("m7", "nordlicht_tv", "jana_draws", "Jana_draws", "@lesh das Fanart ist fertig, schau mal in den Discord", now - 4 * 24 * HOUR, read = true),
)

private fun sampleWhispers(now: Long) = listOf(
    InboxWhisper("w1", "mila", displayName = "Mila", text = "Hey, hast du kurz Zeit? Ich komme mit dem Bot nicht weiter", timestamp = now - 4 * MINUTE, color = 0xFFFF69B4.toInt()),
    InboxWhisper("w2", "bytebaron", displayName = "ByteBaron", text = "Danke fürs Mitmachen gestern, das war echt lustig 😄", timestamp = now - 52 * MINUTE, color = 0xFF1E90FF.toInt()),
    InboxWhisper("w3", "flo_zockt", displayName = "Flo_zockt", text = "Kannst du mir den Link zum Turnier schicken?", timestamp = now - 5 * HOUR, color = 0xFF00FF7F.toInt(), read = true),
    InboxWhisper("w4", "jana_draws", displayName = "Jana_draws", text = "Ich hab dir das Bild per Mail geschickt, sag Bescheid ob es passt", timestamp = now - 28 * HOUR, color = 0xFFDAA520.toInt(), read = true),
    InboxWhisper("w5", "tobi_rennt", displayName = "Tobi_rennt", text = "gg!", timestamp = now - 3 * 24 * HOUR, read = true),
)

/** Draws each `sample:#RRGGBB` avatar as a coloured circle instead of loading it. */
@Composable
private fun sampleImageLoader(): ImageLoader {
    val context = LocalContext.current
    return remember {
        ImageLoader.Builder(context).components {
            add(Interceptor { chain ->
                val color = android.graphics.Color.parseColor(chain.request.data.toString().removePrefix("sample:"))
                SuccessResult(sampleAvatar(color).asImage(), chain.request)
            })
        }.build()
    }
}

private fun sampleAvatar(color: Int): Bitmap {
    val size = 160
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(color)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.argb(70, 255, 255, 255)
        typeface = Typeface.DEFAULT_BOLD
    }
    canvas.drawCircle(size * 0.5f, size * 0.42f, size * 0.2f, paint)
    canvas.drawCircle(size * 0.5f, size * 1.05f, size * 0.38f, paint)
    return bitmap
}
