package me.lesh.chatter.ui.recap

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.selection.toggleable
import androidx.core.content.FileProvider
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lesh.chatter.R
import me.lesh.chatter.channels.ChannelInfo
import me.lesh.chatter.debug.AppLog
import me.lesh.chatter.stats.Streak
import me.lesh.chatter.stats.YearStats
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as DateTextStyle
import java.time.temporal.WeekFields
import me.lesh.chatter.ui.channels.ChannelAvatar
import java.io.File
import kotlin.math.roundToInt

/** The user's name and picture for the card; [pictureUrl] is null without a picture of their own. */
@Immutable
internal class RecapProfile(val name: String, val pictureUrl: String?)

/**
 * The last page: the year on one card, drawn the same every time and shared as a picture in the
 * format of a story. Channels go on it only when the user switches them on; other chatters'
 * names never do.
 */
@Composable
internal fun SummaryPage(year: Int, stats: YearStats, info: Map<String, ChannelInfo>, me: RecapProfile?, imageLoader: ImageLoader, onReplay: () -> Unit) {
    val colors = RecapPage.Summary.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var withChannels by rememberSaveable { mutableStateOf(false) }
    // One picture at a time: a second tap while one is written would write over it.
    var sharing by remember { mutableStateOf(false) }
    // Whether the picture was last drawn with channels, null before it was drawn at all; written
    // while drawing, so no state.
    val drawnWithChannels = remember { arrayOfNulls<Boolean>(1) }
    val picture = rememberGraphicsLayer()
    // One value for the card and for the mark of how it was drawn, read once per composition:
    // read live while drawing, the mark could already say "off" while the card still shows channels.
    val cardWithChannels = withChannels
    // The card takes what the controls leave, but never less than a readable size: in a short
    // window, e.g. split screen, the page scrolls instead of shrinking the card away.
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val cardArea = (maxHeight - CONTROLS_HEIGHT).coerceAtLeast(MIN_CARD_HEIGHT)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        BoxWithConstraints(Modifier.height(cardArea).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val width = min(maxWidth, maxHeight * CARD_WIDTH_DP / CARD_HEIGHT_DP)
            val cardDensity = Density(LocalDensity.current.run { width.toPx() } / CARD_WIDTH_DP, fontScale = 1f)
            Box(
                Modifier
                    .size(width, width * CARD_HEIGHT_DP / CARD_WIDTH_DP)
                    .clip(RoundedCornerShape(20.dp)),
            ) {
                // The card is laid out at one fixed size and scaled, so the picture looks the same
                // on every phone and at every text size.
                CompositionLocalProvider(LocalDensity provides cardDensity) {
                    Box(
                        Modifier
                            .size(CARD_WIDTH_DP.dp, CARD_HEIGHT_DP.dp)
                            .drawWithContent {
                                // Drawn into the picture at full size, and from there onto the screen.
                                val factor = SHARE_WIDTH_PX / size.width
                                picture.record(IntSize(SHARE_WIDTH_PX.roundToInt(), (SHARE_WIDTH_PX * CARD_HEIGHT_DP / CARD_WIDTH_DP).roundToInt())) {
                                    scale(factor, Offset.Zero) { this@drawWithContent.drawContent() }
                                }
                                if (size.width > 0f) drawnWithChannels[0] = cardWithChannels
                                scale(1 / factor, Offset.Zero) { drawLayer(picture) }
                            },
                    ) {
                        ShareCard(year, stats, info.takeIf { cardWithChannels }, me, imageLoader)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = withChannels, enabled = !sharing, role = Role.Switch, onValueChange = { if (!sharing) withChannels = it }),
        ) {
            Text(stringResource(R.string.recap_share_channels), color = colors.content, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = withChannels,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = Ink),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onReplay,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.content),
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) { Text(stringResource(R.string.recap_replay)) }
            Button(
                onClick = onClick@{
                    if (sharing) return@onClick
                    val chosen = withChannels
                    sharing = true
                    scope.launch {
                        // The switch may have changed a moment ago, and the picture is taken from
                        // the card as drawn: it waits until the card was drawn the way it was chosen.
                        // The switch stays locked meanwhile.
                        withFrameNanos { }
                        var frames = 0
                        while (drawnWithChannels[0] != chosen && frames++ < MAX_FRAMES_TO_DRAW) withFrameNanos { }
                        val shared = runCatching {
                            check(drawnWithChannels[0] == chosen) { "The card was not drawn as chosen" }
                            sharePicture(context, picture.toImageBitmap().asAndroidBitmap(), year)
                        }
                        shared.exceptionOrNull()?.let {
                            AppLog.w(TAG, "Could not make the year in review picture", it)
                            Toast.makeText(context, R.string.recap_share_failed, Toast.LENGTH_SHORT).show()
                        }
                        sharing = false
                    }
                },
                enabled = !sharing,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Ink),
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) { Text(stringResource(R.string.recap_share), fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(8.dp))
    }
    }
}

/**
 * The card itself, at [CARD_WIDTH_DP] × [CARD_HEIGHT_DP]. [info] is null without channels; the
 * year's days take their place then.
 */
@Composable
private fun ShareCard(year: Int, stats: YearStats, info: Map<String, ChannelInfo>?, me: RecapProfile?, imageLoader: ImageLoader) {
    val emotes = remember(stats) { stats.topEmotes(3) }
    val channels = remember(stats) { stats.topChannels(3) }
    val longest = remember(stats) { stats.longestStreak() }
    val showChannels = info != null && channels.isNotEmpty()
    val streak = longest?.days ?: 0
    Column(
        Modifier
            .fillMaxSize()
            .background(Violet)
            .padding(28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(ImageVector.vectorResource(R.drawable.ic_chatter_monochrome), contentDescription = null, tint = Lime, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Text("Chatter", color = Paper, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(stats.chatType().title),
                color = Ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Lime)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(year.toString(), color = Lime, fontSize = 84.sp, lineHeight = 84.sp, fontWeight = FontWeight.Black, letterSpacing = (-3).sp)
        Spacer(Modifier.height(6.dp))
        val title = stringResource(R.string.recap_summary_title, year)
        if (me != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PersonPicture(me.name, me.pictureUrl, imageLoader, background = Lime, content = Violet, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(me.name, color = Paper, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(title, color = Paper.copy(alpha = 0.75f), fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        } else {
            Text(title, color = Paper, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        // Full, the card has room only for this much space between its parts.
        Spacer(Modifier.height(16.dp))
        if (showChannels) Spacer(Modifier.weight(1f))
        Row {
            CardNumber(stringResource(R.string.recap_summary_messages), formatCount(stats.sent), Modifier.weight(1f))
            CardNumber(stringResource(R.string.recap_summary_days), formatCount(stats.activeDays.toLong()), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row {
            CardNumber(stringResource(R.string.recap_summary_streak), pluralStringResource(R.plurals.stats_days, streak, streak), Modifier.weight(1f))
            CardNumber(stringResource(R.string.recap_summary_mentions), formatCount(stats.mentions), Modifier.weight(1f))
        }
        if (emotes.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            CardLabel(stringResource(R.string.recap_summary_emotes))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                emotes.forEach { (name, count) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(88.dp)) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Paper.copy(alpha = 0.14f)),
                        ) {
                            AsyncImage(model = count.url, contentDescription = name, imageLoader = imageLoader, modifier = Modifier.size(38.dp))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(name, color = Paper, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (showChannels && info != null) {
            Spacer(Modifier.height(16.dp))
            CardLabel(stringResource(R.string.recap_summary_channels))
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                channels.forEachIndexed { i, (login, _) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", color = Lime, fontSize = 15.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(20.dp))
                        ChannelAvatar(info[login], imageLoader, 26.dp, showLive = false)
                        Spacer(Modifier.width(10.dp))
                        Text(info[login]?.displayName ?: login, color = Paper, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } else {
            // The rest of the card, which would otherwise stay empty in a year without emotes.
            CardDays(year, stats, longest, Modifier.weight(1f).padding(top = 20.dp))
        }
        if (showChannels) Spacer(Modifier.weight(1f))
    }
}

/**
 * The year's days under their label, centred in the space left on the card: as twelve month
 * calendars, four or six to a row, as large as that space allows, or where too little is left for
 * them as one strip of weeks.
 */
@Composable
private fun CardDays(year: Int, stats: YearStats, streak: Streak?, modifier: Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val firstDay = WeekFields.of(locale).firstDayOfWeek
    val max = remember(stats) { (stats.sentPerDay.values.maxOrNull() ?: 0L).coerceAtLeast(1L) }
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val perRow = CALENDARS_PER_ROW.firstOrNull { n ->
            val cell = (maxWidth - CALENDAR_GAP * (n - 1)) / n / 7
            val rows = 12 / n
            CARD_LABEL_HEIGHT + (MONTH_LABEL_HEIGHT + cell * 6) * rows + CALENDAR_GAP * (rows - 1) <= maxHeight
        }
        Column(verticalArrangement = Arrangement.spacedBy(CALENDAR_GAP)) {
            CardLabel(stringResource(R.string.recap_summary_days_grid))
            if (perRow == null) {
                WeekStrip(year, stats, streak, max, firstDay)
                return@Column
            }
            (0 until 12).chunked(perRow).forEach { months ->
                Row(horizontalArrangement = Arrangement.spacedBy(CALENDAR_GAP)) {
                    months.forEach { m ->
                        val month = YearMonth.of(year, m + 1)
                        Column(Modifier.weight(1f)) {
                            Text(
                                month.month.getDisplayName(DateTextStyle.NARROW_STANDALONE, locale),
                                color = Paper.copy(alpha = 0.75f),
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.height(MONTH_LABEL_HEIGHT),
                            )
                            Canvas(Modifier.fillMaxWidth().aspectRatio(7f / 6f)) {
                                val cell = size.width / 7
                                val gap = cell * 0.2f
                                val lead = Math.floorMod(month.atDay(1).dayOfWeek.value - firstDay.value, 7)
                                for (d in 1..month.lengthOfMonth()) {
                                    val date = month.atDay(d)
                                    val slot = lead + d - 1
                                    val inStreak = streak != null && !date.isBefore(streak.from) && !date.isAfter(streak.to)
                                    drawRoundRect(
                                        dayColor(stats.sentPerDay[date.toString()], max, inStreak, CardColors),
                                        topLeft = Offset(slot % 7 * cell + gap / 2, slot / 7 * cell + gap / 2),
                                        size = Size(cell - gap, cell - gap),
                                        cornerRadius = CornerRadius(cell * 0.25f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Every week of [year] as a column of seven days, like the streak page shows it. */
@Composable
private fun WeekStrip(year: Int, stats: YearStats, streak: Streak?, max: Long, firstDay: DayOfWeek) {
    val jan1 = LocalDate.of(year, 1, 1)
    val lead = Math.floorMod(jan1.dayOfWeek.value - firstDay.value, 7)
    val weeks = (lead + jan1.lengthOfYear() + 6) / 7
    Canvas(Modifier.fillMaxWidth().aspectRatio(weeks / 7f)) {
        val cell = size.width / weeks
        val gap = cell * 0.18f
        for (d in 0 until jan1.lengthOfYear()) {
            val date = jan1.plusDays(d.toLong())
            val slot = lead + d
            val inStreak = streak != null && !date.isBefore(streak.from) && !date.isAfter(streak.to)
            drawRoundRect(
                dayColor(stats.sentPerDay[date.toString()], max, inStreak, CardColors),
                topLeft = Offset(slot / 7 * cell + gap / 2, slot % 7 * cell + gap / 2),
                size = Size(cell - gap, cell - gap),
                cornerRadius = CornerRadius(cell * 0.2f),
            )
        }
    }
}

@Composable
private fun CardNumber(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        CardLabel(label)
        Text(value, color = Paper, fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black, maxLines = 1)
    }
}

@Composable
private fun CardLabel(text: String) {
    Text(text, color = Paper.copy(alpha = 0.75f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
}

/**
 * Writes the picture into the cache and offers it to other apps. Each picture gets a name of its
 * own: an app that was given an earlier one may still hold the right to read its address, and
 * must not find the new picture there, e.g. with the channels on. The last [PICTURES_KEPT] stay,
 * so an app that sends the picture on a little later still finds it; clearing the cache under
 * Settings → Storage deletes them. The folder is the one [FileProvider] may hand out.
 */
private suspend fun sharePicture(context: Context, bitmap: Bitmap, year: Int) {
    val uri = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, SHARED_FOLDER).apply { mkdirs() }
        folder.listFiles()?.sortedByDescending { it.lastModified() }?.drop(PICTURES_KEPT - 1)?.forEach { it.delete() }
        val file = File(folder, "chatter-$year-${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        // The chooser shows the picture from the clip, and needs the grant for it too.
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
    AppLog.i(TAG, "Shared the year in review picture")
}

/** The card's violet, with the year's days in lime and the longest streak in white. */
private val CardColors = RecapColors(Violet, Paper, Lime)

/** Largest first: the first that fits the space is taken. */
private val CALENDARS_PER_ROW = listOf(4, 6)
private val CALENDAR_GAP = 10.dp
private val MONTH_LABEL_HEIGHT = 14.dp

/** The label above the calendars with the gap below it. */
private val CARD_LABEL_HEIGHT = 28.dp

/** Roughly what the switch, the buttons and the gaps around them take below the card. */
private val CONTROLS_HEIGHT = 160.dp

/** Below this the card can no longer be read, nor checked before sharing. */
private val MIN_CARD_HEIGHT = 280.dp

/** The card's own size, a story's 9:16. */
private const val CARD_WIDTH_DP = 360f
private const val CARD_HEIGHT_DP = 640f

/** Wide enough for a story on any phone. */
private const val SHARE_WIDTH_PX = 1080f

/** Under the app's cache; see `res/xml/shared_files.xml`. */
internal const val SHARED_FOLDER = "shared"

/** About a second; a card that is not drawn by then is not shared rather than shared as it was. */
private const val MAX_FRAMES_TO_DRAW = 60

/** A few hundred kilobytes each. */
private const val PICTURES_KEPT = 3

private const val TAG = "Recap"
