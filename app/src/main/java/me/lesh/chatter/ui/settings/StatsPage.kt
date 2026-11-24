package me.lesh.chatter.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.AsyncImage
import me.lesh.chatter.R
import me.lesh.chatter.stats.PersonCount
import me.lesh.chatter.stats.Stats
import me.lesh.chatter.ui.inbox.PersonAvatar
import me.lesh.chatter.ui.theme.isAppInDarkTheme
import me.lesh.chatter.ui.theme.readableNameColor
import me.lesh.chatter.ui.FormSheet
import me.lesh.chatter.ui.MainViewModel
import me.lesh.chatter.ui.channels.ChannelAvatar
import me.lesh.chatter.ui.recap.Ink
import me.lesh.chatter.ui.recap.Lime
import me.lesh.chatter.ui.recap.Paper
import me.lesh.chatter.ui.recap.Violet
import java.text.DateFormat
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.WeekFields
import java.util.Date
import kotlin.math.sqrt

/** The user's chat stats, counted on this device and never sent; can be reset at the bottom. */
@Composable
internal fun StatsPage(vm: MainViewModel, onChannels: () -> Unit) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val info by vm.channelInfo.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val thisYear = stats.year(today.year)
    val busiest = remember(stats.sentPerChannel) { stats.busiestChannels.take(5) }
    val week = remember(stats.backgroundReceived) { stats.backgroundByChannel(Stats.KEEP_DAYS) }
    val backgroundToday = remember(stats.backgroundReceived) { stats.backgroundByChannel(1).toMap() }
    val outliers = remember(week) { Stats.outliers(week) }
    val recapYears = remember(stats.years) { stats.recapYears(today) }

    recapYears.forEach { year ->
        EnterInOrder { RecapCard(year) { vm.openRecap(year) } }
    }
    EnterInOrder {
        val streak = remember(stats.activeDays) { stats.streak() }
        StatGrid(
            listOf(
                R.string.settings_stats_sent to formatNumber(stats.sent),
                R.string.settings_stats_mentions to formatNumber(stats.mentions),
                R.string.settings_stats_active_days_short to formatNumber(stats.activeDays.size.toLong()),
                R.string.settings_stats_streak to pluralStringResource(R.plurals.stats_days, streak, streak),
            ),
        )
    }
    SettingsGroup(R.string.settings_stats_group_activity, R.string.settings_stats_activity_hint) {
        item {
            Column(Modifier.padding(16.dp)) {
                ActivityGrid(stats, today)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val longest = remember(thisYear.sentPerDay) { thisYear.longestStreak()?.days ?: 0 }
                    Text(
                        stringResource(R.string.settings_stats_longest_streak) + " · " +
                            pluralStringResource(R.plurals.stats_days, longest, longest),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    HeatLegend()
                }
            }
        }
        if (stats.since > 0) {
            item { StatRow(R.string.settings_stats_since, formatDay(stats.since)) }
        }
        item { StatRow(R.string.settings_stats_received, formatNumber(stats.received)) }
    }
    if (thisYear.sentPerHour.sum() > 0) {
        SettingsGroup(R.string.settings_stats_group_hours, R.string.settings_stats_hours_hint) {
            item { HourChart(thisYear.sentPerHour) }
        }
    }
    SettingsGroup(R.string.settings_stats_group_channels) {
        if (busiest.isEmpty()) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_stats_no_channels)) },
                    colors = transparentItem(),
                )
            }
        }
        val most = busiest.firstOrNull()?.second ?: 1L
        busiest.forEach { (login, count) ->
            item {
                ListItem(
                    leadingContent = { ChannelAvatar(info[login], vm.imageLoader, 36.dp, showLive = false) },
                    headlineContent = { Text(info[login]?.displayName ?: login, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { ShareBar(count.toFloat() / most) },
                    trailingContent = { Text(formatNumber(count), fontWeight = FontWeight.Medium) },
                    colors = transparentItem(),
                )
            }
        }
    }
    val talkedTo = remember(thisYear.talkedTo) { thisYear.topTalkedTo(PEOPLE_SHOWN) }
    val mentionedBy = remember(thisYear.mentionedBy) { thisYear.topMentionedBy(PEOPLE_SHOWN) }
    val pictures by vm.userPictures.collectAsStateWithLifecycle()
    LaunchedEffect(talkedTo, mentionedBy) { vm.requestPictures((talkedTo + mentionedBy).map { it.first }) }
    PeopleGroup(R.string.settings_stats_group_people, R.string.settings_stats_people_hint, R.string.settings_stats_no_people, talkedTo, pictures, vm.imageLoader)
    PeopleGroup(R.string.settings_stats_group_mentioned_by, R.string.settings_stats_mentioned_by_hint, R.string.settings_stats_no_mentioned_by, mentionedBy, pictures, vm.imageLoader)
    SettingsGroup(R.string.settings_stats_group_emotes, R.string.settings_stats_emotes_hint) {
        item {
            val emotes = remember(thisYear.emotes) { thisYear.topEmotes(EMOTES_SHOWN) }
            if (emotes.isEmpty()) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_stats_no_emotes)) },
                    colors = transparentItem(),
                )
            } else {
                EmoteGrid(emotes.map { (name, count) -> Triple(name, count.url, count.atLeast) }, vm.imageLoader)
            }
        }
    }
    // What staying joined costs while the app is closed, per channel, so the user can decide
    // whether to keep a very busy one overnight.
    SettingsGroup(R.string.settings_stats_group_background) {
        if (week.isEmpty()) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_stats_background_none)) },
                    colors = transparentItem(),
                )
            }
        }
        week.take(BACKGROUND_CHANNELS_SHOWN).forEach { (login, count) ->
            item {
                val busy = login in outliers
                ListItem(
                    headlineContent = { Text(info[login]?.displayName ?: login) },
                    supportingContent = {
                        Column {
                            Text(
                                stringResource(
                                    R.string.settings_stats_today_week,
                                    formatNumber(backgroundToday[login] ?: 0), formatNumber(count),
                                )
                            )
                            if (busy) {
                                Text(
                                    stringResource(R.string.settings_stats_background_busy),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    trailingContent = if (busy) {
                        { NavChevron() }
                    } else null,
                    colors = transparentItem(),
                    modifier = if (busy) Modifier.tileClickable(onClick = onChannels) else Modifier,
                )
            }
        }
    }
    SettingsGroup {
        item(R.string.settings_stats_reset) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_stats_reset)) },
                supportingContent = { Text(stringResource(R.string.settings_stats_reset_hint)) },
                trailingContent = {
                    OutlinedButton(onClick = { confirmReset = true }) {
                        Text(stringResource(R.string.settings_stats_reset_action))
                    }
                },
                colors = transparentItem(),
            )
        }
    }

    if (confirmReset) {
        FormSheet(
            title = stringResource(R.string.settings_stats_reset),
            text = stringResource(R.string.settings_stats_reset_confirm),
            confirmLabel = stringResource(R.string.settings_stats_reset_action),
            destructive = true,
            onConfirm = vm::resetStats,
            onDismiss = { confirmReset = false },
        )
    }
}

/** The way into the look back at [year], in the look back's own colours. */
@Composable
private fun RecapCard(year: Int, onClick: () -> Unit) {
    Surface(
        color = Violet,
        shape = RoundedCornerShape(OUTER_CORNER),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Icon(ImageVector.vectorResource(R.drawable.ic_chatter_monochrome), contentDescription = null, tint = Lime, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.recap_title, year), color = Paper, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                Text(stringResource(R.string.settings_stats_recap_hint), color = Paper.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
            }
            Box(Modifier.size(36.dp).clip(CircleShape).background(Lime), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Ink)
            }
        }
    }
}

/**
 * Two tiles per row, grouped like the settings tiles: strong corners on the outside of the
 * group, slight ones between tiles.
 */
@Composable
private fun StatGrid(tiles: List<Pair<Int, String>>) {
    val rows = tiles.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEachIndexed { c, (label, value) ->
                    val top = if (r == 0) OUTER_CORNER else INNER_CORNER
                    val bottom = if (r == rows.lastIndex) OUTER_CORNER else INNER_CORNER
                    val start = c == 0
                    val end = c == row.lastIndex
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(
                            topStart = if (start) top else INNER_CORNER,
                            topEnd = if (end) top else INNER_CORNER,
                            bottomStart = if (start) bottom else INNER_CORNER,
                            bottomEnd = if (end) bottom else INNER_CORNER,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                            Text(
                                stringResource(label),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                value,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The last [ACTIVITY_WEEKS] weeks as squares, one column per week, darker the more was sent that
 * day. Days from before messages were counted per day show at the lightest step.
 */
@Composable
private fun ActivityGrid(stats: Stats, today: LocalDate) {
    val locale = LocalConfiguration.current.locales[0]
    val firstDay = WeekFields.of(locale).firstDayOfWeek
    val weekStart = today.minusDays(Math.floorMod(today.dayOfWeek.value - firstDay.value, 7).toLong())
    val start = weekStart.minusWeeks(ACTIVITY_WEEKS - 1L)
    val counts = remember(stats.years, stats.activeDays) {
        buildMap {
            stats.activeDays.forEach { put(it, 0L) }
            stats.years.values.forEach { year -> year.sentPerDay.forEach { (day, n) -> put(day, maxOf(n, get(day) ?: 0L)) } }
        }
    }
    // Over the days shown only, so a busy day long ago does not pale the weeks in view.
    val max = remember(counts, start) {
        counts.filterKeys { it >= start.toString() }.values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    }
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val full = MaterialTheme.colorScheme.primary
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ACTIVITY_WEEKS / 7f)
            // A picture of the days says nothing to TalkBack; the numbers around it do.
            .clearAndSetSemantics { },
    ) {
        val cell = size.width / ACTIVITY_WEEKS
        val gap = cell * 0.16f
        var day = start
        while (!day.isAfter(today)) {
            val index = (day.toEpochDay() - start.toEpochDay()).toInt()
            val n = counts[day.toString()]
            drawRoundRect(
                heatColor(n, max, empty, full),
                topLeft = Offset((index / 7) * cell + gap / 2, (index % 7) * cell + gap / 2),
                size = Size(cell - gap, cell - gap),
                cornerRadius = CornerRadius(cell * 0.22f),
            )
            day = day.plusDays(1)
        }
    }
}

private fun heatColor(n: Long?, max: Long, empty: Color, full: Color): Color = when {
    n == null -> empty
    n == 0L -> full.copy(alpha = HEAT_STEPS[0])
    else -> full.copy(alpha = HEAT_STEPS[((sqrt(n.toFloat() / max) * (HEAT_STEPS.size - 1)).toInt()).coerceIn(0, HEAT_STEPS.lastIndex)])
}

@Composable
private fun HeatLegend() {
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val full = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(stringResource(R.string.settings_stats_less), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(2.dp))
        (listOf(empty) + HEAT_STEPS.map { full.copy(alpha = it) }).forEach { color ->
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        }
        Spacer(Modifier.width(2.dp))
        Text(stringResource(R.string.settings_stats_more), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Messages per hour of the day as bars, the busiest hour named above them. */
@Composable
private fun HourChart(hours: List<Long>) {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }
    val peak = hours.indices.maxBy { hours[it] }
    val max = hours[peak].coerceAtLeast(1L)
    Column(Modifier.padding(16.dp)) {
        Text(
            stringResource(R.string.recap_rhythm_peak, LocalTime.of(peak, 0).format(format)),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth().height(72.dp).clearAndSetSemantics { },
        ) {
            hours.forEachIndexed { h, n ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight((n.toFloat() / max).coerceAtLeast(0.04f))
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(if (h == peak) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            listOf(0, 6, 12, 18).forEach { h ->
                Text(
                    LocalTime.of(h, 0).format(format),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** How a channel's count compares with the busiest one's. */
/** The [people] of one list, most first, with their pictures where known; [empty] while there are none. */
@Composable
private fun PeopleGroup(
    @StringRes title: Int,
    @StringRes hint: Int,
    @StringRes empty: Int,
    people: List<Pair<String, PersonCount>>,
    pictures: Map<String, String?>,
    imageLoader: ImageLoader,
) {
    val dark = isAppInDarkTheme()
    SettingsGroup(title, hint) {
        if (people.isEmpty()) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(empty)) },
                    colors = transparentItem(),
                )
            }
        }
        val most = people.firstOrNull()?.second?.atLeast?.coerceAtLeast(1L) ?: 1L
        people.forEach { (login, person) ->
            item {
                ListItem(
                    leadingContent = { PersonAvatar(person.name, readableNameColor(null, login, dark), pictures[login], imageLoader, size = 36.dp) },
                    headlineContent = { Text(person.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { ShareBar(person.atLeast.toFloat() / most) },
                    trailingContent = { Text(formatNumber(person.atLeast), fontWeight = FontWeight.Medium) },
                    colors = transparentItem(),
                )
            }
        }
    }
}

@Composable
private fun ShareBar(share: Float) {
    Box(
        Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(share.coerceIn(0.03f, 1f)).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
    }
}

/** Emotes as tiles of four per row, each with its name and how often it was sent. */
@Composable
private fun EmoteGrid(emotes: List<Triple<String, String, Long>>, imageLoader: ImageLoader) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        emotes.chunked(EMOTES_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (name, url, count) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(EMOTE_TILE)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        ) {
                            AsyncImage(model = url, contentDescription = name, imageLoader = imageLoader, modifier = Modifier.size(EMOTE_TILE * 0.66f))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatNumber(count), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                repeat(EMOTES_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StatRow(label: Int, value: String) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        trailingContent = { Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium) },
        colors = transparentItem(),
    )
}

private val OUTER_CORNER = 24.dp
private val INNER_CORNER = 4.dp

/** Twenty weeks fill a phone's width with squares still big enough to tell apart. */
private const val ACTIVITY_WEEKS = 20

/** The alpha of the accent for busier and busier days. */
private val HEAT_STEPS = listOf(0.45f, 0.62f, 0.8f, 1f)

private const val EMOTES_SHOWN = 8
private const val PEOPLE_SHOWN = 5
private const val EMOTES_PER_ROW = 4
private val EMOTE_TILE: Dp = 56.dp

/** How many channels the background figures list; the rest cost little. */
private const val BACKGROUND_CHANNELS_SHOWN = 8

/** Grouped per the phone's locale, so large numbers stay readable. */
private fun formatNumber(n: Long): String = NumberFormat.getIntegerInstance().format(n)

private fun formatDay(at: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at))
