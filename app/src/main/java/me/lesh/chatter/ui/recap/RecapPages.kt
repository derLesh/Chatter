package me.lesh.chatter.ui.recap

import android.icu.text.CompactDecimalFormat
import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import me.lesh.chatter.R
import me.lesh.chatter.channels.ChannelInfo
import me.lesh.chatter.stats.ChatType
import me.lesh.chatter.stats.PersonCount
import me.lesh.chatter.stats.Streak
import me.lesh.chatter.stats.YearStats
import me.lesh.chatter.ui.channels.ChannelAvatar
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle as DateTextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** The pages of a look back, in order; [recapPages] leaves out those the year has nothing for. */
internal enum class RecapPage(val colors: RecapColors) {
    Intro(RecapColors(Violet, Paper, Lime)),
    Messages(RecapColors(Lime, Ink, Violet)),
    Channels(RecapColors(Ink, Paper, Lime)),
    Emotes(RecapColors(Coral, Ink, Paper)),
    Rhythm(RecapColors(Blue, Paper, Sun)),
    Streak(RecapColors(Sun, Ink, Coral)),
    Mentions(RecapColors(Mint, Ink, Paper)),
    Type(RecapColors(Pink, Ink, Violet)),
    Summary(RecapColors(Ink, Paper, Lime)),
}

/**
 * Each page is a poster of its own, like the looks back of music and chat apps, so they keep their
 * colours in the light and the dark theme.
 */
@Immutable
internal class RecapColors(val background: Color, val content: Color, val accent: Color)

internal val Violet = Color(0xFF7C3AED)
internal val Lime = Color(0xFFD7FF3C)
internal val Ink = Color(0xFF15121C)
internal val Paper = Color(0xFFF7F3EC)
private val Coral = Color(0xFFFF6B4F)
private val Blue = Color(0xFF2F5BFF)
private val Sun = Color(0xFFFFC83D)
private val Mint = Color(0xFF2FD3AE)
private val Pink = Color(0xFFFF8AC9)

internal fun recapPages(stats: YearStats): List<RecapPage> = RecapPage.entries.filter { page ->
    when (page) {
        RecapPage.Channels -> stats.sentPerChannel.isNotEmpty()
        RecapPage.Emotes -> stats.emotes.isNotEmpty()
        RecapPage.Rhythm -> stats.peakHour() != null || stats.busiestDay() != null
        RecapPage.Streak -> (stats.longestStreak()?.days ?: 0) >= 2
        RecapPage.Mentions -> stats.mentions > 0 || stats.talkedTo.isNotEmpty() || stats.mentionedBy.isNotEmpty()
        else -> true
    }
}

@Composable
internal fun RecapPageContent(
    page: RecapPage,
    year: Int,
    stats: YearStats,
    info: Map<String, ChannelInfo>,
    imageLoader: ImageLoader,
    staticImageLoader: ImageLoader,
    /** Profile picture addresses of chatters by login, null for one without a picture. */
    pictures: Map<String, String?>,
    me: RecapProfile?,
    onReplay: () -> Unit,
    onNeedsScrolling: (Boolean) -> Unit,
) {
    val colors = page.colors
    if (page == RecapPage.Summary) {
        SummaryPage(year, stats, info, me, staticImageLoader, onReplay)
        return
    }
    // Centred while it fits, scrolling when it does not, e.g. in landscape or with large text.
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val scroll = rememberScrollState()
    val needsScrolling = scroll.maxValue > 0
    LaunchedEffect(needsScrolling) { onNeedsScrolling(needsScrolling) }
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .verticalScroll(scroll)
            .heightIn(min = maxHeight)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        when (page) {
            RecapPage.Intro -> IntroPage(year, colors)
            RecapPage.Messages -> MessagesPage(stats, colors)
            RecapPage.Channels -> ChannelsPage(stats, info, imageLoader, colors)
            RecapPage.Emotes -> EmotesPage(stats, imageLoader, colors)
            RecapPage.Rhythm -> RhythmPage(stats, colors)
            RecapPage.Streak -> StreakPage(year, stats, colors)
            RecapPage.Mentions -> MentionsPage(year, stats, pictures, imageLoader, colors)
            RecapPage.Type -> TypePage(stats.chatType(), colors)
            RecapPage.Summary -> Unit
        }
    }
    }
}

@Composable
private fun ColumnScope.IntroPage(year: Int, colors: RecapColors) {
    Rise(0) {
        Icon(ImageVector.vectorResource(R.drawable.ic_chatter_monochrome), contentDescription = null, tint = colors.accent, modifier = Modifier.size(56.dp))
    }
    Spacer(Modifier.height(24.dp))
    Rise(1) { Kicker(stringResource(R.string.recap_intro_kicker), colors.content) }
    Rise(2) { Headline(year.toString(), colors.accent, maxSize = 200.sp) }
    Spacer(Modifier.height(48.dp))
    Rise(4) {
        Text(
            stringResource(R.string.recap_intro_hint),
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
            color = colors.content.copy(alpha = 0.75f),
        )
    }
}

@Composable
private fun ColumnScope.MessagesPage(stats: YearStats, colors: RecapColors) {
    Rise(0) { Kicker(stringResource(R.string.recap_messages_kicker), colors.content) }
    Rise(1) { Headline(formatCount(rememberCountUp(stats.sent)), colors.content) }
    Rise(2) { Kicker(pluralStringResource(R.plurals.recap_messages_unit, stats.sent.toQuantity()), colors.content) }
    Spacer(Modifier.height(32.dp))
    val days = stats.activeDays
    if (days > 0) {
        Rise(3) {
            Sticker(pluralStringResource(R.plurals.recap_messages_days, days, formatCount(days.toLong())), colors.accent, colors.background)
        }
        if (stats.sent / days >= 2) {
            Spacer(Modifier.height(12.dp))
            Rise(4) { Line(stringResource(R.string.recap_messages_per_day, formatCount(stats.sent / days)), colors.content) }
        }
    }
    if (stats.received > stats.sent) {
        Spacer(Modifier.height(12.dp))
        Rise(5) { Line(stringResource(R.string.recap_messages_received, formatCompact(stats.received)), colors.content) }
    }
}

@Composable
private fun ColumnScope.ChannelsPage(stats: YearStats, info: Map<String, ChannelInfo>, imageLoader: ImageLoader, colors: RecapColors) {
    val top = remember(stats) { stats.topChannels(5) }
    val (first, firstCount) = top.first()
    Rise(0) { Kicker(stringResource(R.string.recap_channels_kicker), colors.content) }
    Spacer(Modifier.height(20.dp))
    Rise(1) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelAvatar(info[first], imageLoader, 72.dp, showLive = false)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Headline(info[first]?.displayName ?: first, colors.accent, maxSize = 56.sp, minSize = 20.sp)
                Line(pluralStringResource(R.plurals.recap_count_messages, firstCount.toQuantity(), formatCount(firstCount)), colors.content)
            }
        }
    }
    Spacer(Modifier.height(28.dp))
    top.drop(1).forEachIndexed { i, (login, count) ->
        Rise(2 + i) {
            RankRow(
                rank = i + 2,
                share = count.toFloat() / firstCount,
                colors = colors,
                leading = { ChannelAvatar(info[login], imageLoader, 36.dp, showLive = false) },
                name = info[login]?.displayName ?: login,
                count = formatCount(count),
            )
        }
        Spacer(Modifier.height(12.dp))
    }
    if (stats.sentPerChannel.size > top.size) {
        Spacer(Modifier.height(8.dp))
        Rise(7) {
            val total = stats.sentPerChannel.size
            Line(pluralStringResource(R.plurals.recap_channels_total, total, formatCount(total.toLong())), colors.content.copy(alpha = 0.8f))
        }
    }
}

/**
 * A chatter's picture, or their first letter on a disc while it loads, when they have none, or when
 * the data saver keeps pictures off.
 */
@Composable
internal fun PersonPicture(name: String, pictureUrl: String?, imageLoader: ImageLoader, background: Color, content: Color, size: Dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).clip(CircleShape).background(background),
    ) {
        Text(
            name.take(1).uppercase(),
            color = content,
            fontWeight = FontWeight.Black,
            fontSize = (size.value * 0.45f).sp,
        )
        if (pictureUrl != null) {
            AsyncImage(model = pictureUrl, contentDescription = null, imageLoader = imageLoader, modifier = Modifier.matchParentSize())
        }
    }
}

@Composable
private fun ColumnScope.EmotesPage(stats: YearStats, imageLoader: ImageLoader, colors: RecapColors) {
    val top = remember(stats) { stats.topEmotes(5) }
    val (name, first) = top.first()
    Rise(0) { Kicker(stringResource(R.string.recap_emotes_kicker), colors.content) }
    Spacer(Modifier.height(20.dp))
    Rise(1) {
        val bounce = rememberInfiniteTransition(label = "emote-bounce")
        val lift by bounce.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "lift")
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(152.dp)
                .clip(RoundedCornerShape(36.dp))
                .background(colors.accent),
        ) {
            AsyncImage(
                model = largerEmote(first.url),
                contentDescription = name,
                imageLoader = imageLoader,
                modifier = Modifier
                    .size(112.dp)
                    .graphicsLayer { translationY = -lift * 8.dp.toPx() },
            )
        }
    }
    Spacer(Modifier.height(20.dp))
    Rise(2) { Headline(name, colors.content, maxSize = 64.sp, minSize = 24.sp) }
    Rise(3) { Line(pluralStringResource(R.plurals.recap_emote_uses, first.atLeast.toQuantity(), formatCount(first.atLeast)), colors.content) }
    if (top.size > 1) {
        Spacer(Modifier.height(28.dp))
        Rise(4) { Line(stringResource(R.string.recap_emotes_more), colors.content.copy(alpha = 0.8f)) }
        Spacer(Modifier.height(12.dp))
        Rise(5) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                top.drop(1).forEach { (other, count) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(20.dp))
                                .background(colors.content.copy(alpha = 0.12f)),
                        ) {
                            AsyncImage(model = count.url, contentDescription = other, imageLoader = imageLoader, modifier = Modifier.fillMaxSize(0.66f))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(other, color = colors.content, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatCount(count.atLeast), color = colors.content.copy(alpha = 0.75f), fontSize = 12.sp)
                    }
                }
                // Keeps the tiles the same size when fewer than four follow.
                repeat(4 - (top.size - 1)) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ColumnScope.RhythmPage(stats: YearStats, colors: RecapColors) {
    val locale = currentLocale()
    val peak = remember(stats) { stats.peakHour() }
    val busiest = remember(stats) { stats.busiestDay() }
    val weekday = remember(stats) { stats.favoriteWeekday() }
    val months = remember(stats) { stats.sentPerMonth() }
    Rise(0) { Kicker(stringResource(R.string.recap_rhythm_kicker), colors.content) }
    Spacer(Modifier.height(20.dp))
    if (peak != null) {
        Rise(1) { Headline(stringResource(R.string.recap_rhythm_peak, formatHour(peak, locale)), colors.accent, maxSize = 52.sp, minSize = 24.sp) }
        Spacer(Modifier.height(12.dp))
        Rise(2) {
            Column {
                Line(stringResource(R.string.recap_rhythm_hours), colors.content.copy(alpha = 0.8f))
                Spacer(Modifier.height(8.dp))
                Bars(stats.sentPerHour, highlight = peak, colors = colors, height = 96.dp)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    listOf(0, 6, 12, 18).forEach { h ->
                        Text(formatHour(h, locale), color = colors.content.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    // The first year is counted per day only from late on, so a chart of two months says little.
    if (months.count { it > 0 } >= 3) {
        Rise(3) {
            Column {
                Line(stringResource(R.string.recap_rhythm_months), colors.content.copy(alpha = 0.8f))
                Spacer(Modifier.height(8.dp))
                Bars(months, highlight = months.indices.maxBy { months[it] }, colors = colors, height = 72.dp)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Month.entries.forEach { m ->
                        Text(
                            m.getDisplayName(DateTextStyle.NARROW_STANDALONE, locale),
                            color = colors.content.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        if (busiest != null) {
            Rise(4, Modifier.weight(1f)) {
                Fact(
                    label = stringResource(R.string.recap_rhythm_busiest_day),
                    value = formatDay(busiest.first, locale, "EEEEdMMMM"),
                    detail = pluralStringResource(R.plurals.recap_count_messages, busiest.second.toQuantity(), formatCount(busiest.second)),
                    colors = colors,
                )
            }
        }
        if (weekday != null) {
            Rise(5, Modifier.weight(1f)) {
                Fact(
                    label = stringResource(R.string.recap_rhythm_weekday),
                    value = weekday.getDisplayName(DateTextStyle.FULL_STANDALONE, locale),
                    detail = null,
                    colors = colors,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.StreakPage(year: Int, stats: YearStats, colors: RecapColors) {
    val locale = currentLocale()
    val streak = remember(stats) { stats.longestStreak() } ?: return
    Rise(0) { Kicker(stringResource(R.string.recap_streak_kicker), colors.content) }
    Rise(1) { Headline(formatCount(rememberCountUp(streak.days.toLong())), colors.content, maxSize = 160.sp) }
    Rise(2) { Kicker(pluralStringResource(R.plurals.recap_streak_unit, streak.days), colors.content) }
    Spacer(Modifier.height(12.dp))
    Rise(3) {
        Line(
            stringResource(R.string.recap_streak_range, formatDay(streak.from, locale, "dMMMM"), formatDay(streak.to, locale, "dMMMM")),
            colors.content,
        )
    }
    Spacer(Modifier.height(28.dp))
    Rise(4) { YearGrid(year, stats, streak, colors, locale) }
    Spacer(Modifier.height(12.dp))
    Rise(5) {
        val daysInYear = LocalDate.of(year, 1, 1).lengthOfYear()
        Line(stringResource(R.string.recap_streak_active, formatCount(stats.activeDays.toLong()), formatCount(daysInYear.toLong())), colors.content)
    }
}

@Composable
private fun ColumnScope.MentionsPage(year: Int, stats: YearStats, pictures: Map<String, String?>, imageLoader: ImageLoader, colors: RecapColors) {
    val mentionedBy = remember(stats) { stats.topMentionedBy(PEOPLE_SHOWN) }
    val talkedTo = remember(stats) { stats.topTalkedTo(PEOPLE_SHOWN) }
    val withPeople = mentionedBy.isNotEmpty() || talkedTo.isNotEmpty()
    if (stats.mentions > 0) {
        Rise(0) { Kicker(stringResource(R.string.recap_mentions_kicker), colors.content) }
        Rise(1) { Headline(formatCount(rememberCountUp(stats.mentions)), colors.content, maxSize = if (withPeople) 112.sp else 160.sp) }
        Rise(2) { Kicker(pluralStringResource(R.plurals.recap_mentions_unit, stats.mentions.toQuantity()), colors.content) }
        // From the first counted day: the first year may have been counted for a few weeks only.
        val from = stats.firstCountedDay(year)
        val to = minOf(LocalDate.now(), LocalDate.of(year, 12, 31))
        val weeks = (to.toEpochDay() - from.toEpochDay() + 1) / 7.0
        val perWeek = (stats.mentions / weeks.coerceAtLeast(1.0)).roundToLong()
        if (perWeek >= 1) {
            Spacer(Modifier.height(20.dp))
            Rise(3) { Sticker(stringResource(R.string.recap_mentions_per_week, formatCount(perWeek)), colors.accent, colors.content) }
        }
    }
    if (mentionedBy.isNotEmpty()) {
        if (stats.mentions > 0) Spacer(Modifier.height(32.dp))
        Rise(4) { PeopleList(stringResource(R.string.recap_mentions_by), mentionedBy, pictures, imageLoader, colors) }
    }
    if (talkedTo.isNotEmpty()) {
        if (stats.mentions > 0 || mentionedBy.isNotEmpty()) Spacer(Modifier.height(24.dp))
        Rise(5) { PeopleList(stringResource(R.string.recap_mentions_to), talkedTo, pictures, imageLoader, colors) }
    }
    if (withPeople) {
        Spacer(Modifier.height(16.dp))
        Rise(6) {
            Text(
                stringResource(R.string.recap_people_hint),
                color = colors.content.copy(alpha = 0.7f),
                style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            )
        }
    }
}

@Composable
private fun PeopleList(title: String, people: List<Pair<String, PersonCount>>, pictures: Map<String, String?>, imageLoader: ImageLoader, colors: RecapColors) {
    Column {
        Text(title, color = colors.content, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp))
        Spacer(Modifier.height(6.dp))
        people.forEach { (login, person) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                PersonPicture(person.name, pictures[login], imageLoader, background = colors.content, content = colors.accent, size = 36.dp)
                Spacer(Modifier.width(12.dp))
                Text(person.name, color = colors.content, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(formatCount(person.atLeast), color = colors.content.copy(alpha = 0.8f), fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun ColumnScope.TypePage(type: ChatType, colors: RecapColors) {
    Rise(0) { Kicker(stringResource(R.string.recap_type_kicker), colors.content) }
    Spacer(Modifier.height(24.dp))
    Rise(1) {
        val spin = rememberInfiniteTransition(label = "type-spin")
        val turn by spin.animateFloat(-6f, 6f, infiniteRepeatable(tween(2_400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "turn")
        Canvas(Modifier.size(168.dp).graphicsLayer { rotationZ = turn }) { drawTypeMark(type, colors) }
    }
    Spacer(Modifier.height(24.dp))
    Rise(2) { Headline(stringResource(type.title), colors.content, maxSize = 72.sp, minSize = 28.sp) }
    Spacer(Modifier.height(8.dp))
    Rise(3) { Line(stringResource(type.hint), colors.content) }
}

internal val ChatType.title: Int
    get() = when (this) {
        ChatType.NightOwl -> R.string.recap_type_night_owl
        ChatType.EarlyBird -> R.string.recap_type_early_bird
        ChatType.EmoteArtist -> R.string.recap_type_emote_artist
        ChatType.Regular -> R.string.recap_type_regular
        ChatType.Explorer -> R.string.recap_type_explorer
        ChatType.AllRounder -> R.string.recap_type_all_rounder
    }

private val ChatType.hint: Int
    get() = when (this) {
        ChatType.NightOwl -> R.string.recap_type_night_owl_hint
        ChatType.EarlyBird -> R.string.recap_type_early_bird_hint
        ChatType.EmoteArtist -> R.string.recap_type_emote_artist_hint
        ChatType.Regular -> R.string.recap_type_regular_hint
        ChatType.Explorer -> R.string.recap_type_explorer_hint
        ChatType.AllRounder -> R.string.recap_type_all_rounder_hint
    }

/** A simple mark per type, drawn rather than an emoji so it matches the posters. */
private fun DrawScope.drawTypeMark(type: ChatType, colors: RecapColors) {
    val r = size.minDimension / 2
    val c = center
    when (type) {
        ChatType.NightOwl -> {
            // A crescent: the moon with a disc of the background taken out.
            drawCircle(colors.accent, r * 0.9f, c)
            drawCircle(colors.background, r * 0.78f, c + Offset(r * 0.38f, -r * 0.24f))
        }
        ChatType.EarlyBird -> {
            repeat(12) { i ->
                rotate(i * 30f, c) {
                    drawRoundRect(colors.accent, Offset(c.x - r * 0.06f, c.y - r), Size(r * 0.12f, r * 0.26f), CornerRadius(r * 0.06f))
                }
            }
            drawCircle(colors.accent, r * 0.52f, c)
        }
        ChatType.EmoteArtist -> {
            drawCircle(colors.accent, r * 0.9f, c)
            drawCircle(colors.background, r * 0.11f, c + Offset(-r * 0.32f, -r * 0.2f))
            drawCircle(colors.background, r * 0.11f, c + Offset(r * 0.32f, -r * 0.2f))
            drawArc(
                colors.background, startAngle = 20f, sweepAngle = 140f, useCenter = false,
                topLeft = c + Offset(-r * 0.5f, -r * 0.35f), size = Size(r, r * 0.9f),
                style = Stroke(width = r * 0.12f, cap = StrokeCap.Round),
            )
        }
        ChatType.Regular -> {
            // A chat bubble: one place the user keeps coming back to.
            drawPath(bubble(Offset(c.x - r * 0.85f, c.y - r * 0.7f), Size(r * 1.7f, r * 1.2f), r * 0.3f), colors.accent)
            repeat(3) { i -> drawCircle(colors.background, r * 0.1f, Offset(c.x - r * 0.42f + i * r * 0.42f, c.y - r * 0.1f)) }
        }
        ChatType.Explorer -> {
            // A four-pointed star, like a compass rose.
            val path = Path().apply {
                moveTo(c.x, c.y - r)
                quadraticTo(c.x, c.y, c.x + r, c.y)
                quadraticTo(c.x, c.y, c.x, c.y + r)
                quadraticTo(c.x, c.y, c.x - r, c.y)
                quadraticTo(c.x, c.y, c.x, c.y - r)
                close()
            }
            drawPath(path, colors.accent)
        }
        ChatType.AllRounder -> {
            repeat(8) { i ->
                rotate(i * 45f, c) { drawCircle(colors.accent, r * 0.17f, Offset(c.x, c.y - r * 0.72f)) }
            }
            drawCircle(colors.accent, r * 0.3f, c)
        }
    }
}

/** A speech bubble with its tail at the bottom left, the motif of a chat app. */
private fun bubble(topLeft: Offset, size: Size, corner: Float): Path = Path().apply {
    addRoundRect(RoundRect(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height, CornerRadius(corner)))
    moveTo(topLeft.x + size.width * 0.18f, topLeft.y + size.height - 1f)
    lineTo(topLeft.x + size.width * 0.12f, topLeft.y + size.height * 1.32f)
    lineTo(topLeft.x + size.width * 0.42f, topLeft.y + size.height - 1f)
    close()
}

/** Big, slowly drifting shapes behind each page, in its accent colour, kept faint so text stays readable. */
@Composable
internal fun Decorations(page: RecapPage, colors: RecapColors) {
    val drift = rememberInfiniteTransition(label = "recap-drift")
    val t by drift.animateFloat(0f, 1f, infiniteRepeatable(tween(9_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "t")
    val tint = colors.accent.copy(alpha = 0.22f)
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val m = size.minDimension
        when (page.ordinal % 4) {
            0 -> {
                drawPath(bubble(Offset(w * 0.38f, h * 0.62f + t * 24f), Size(m * 0.9f, m * 0.62f), m * 0.16f), tint)
                drawCircle(tint, m * 0.3f, Offset(w * 0.05f, h * 0.08f - t * 16f))
            }
            1 -> {
                drawCircle(tint, m * 0.55f, Offset(w * 1.0f, h * 0.18f + t * 20f))
                drawCircle(tint, m * 0.12f, Offset(w * 0.12f, h * 0.9f - t * 20f), style = Stroke(m * 0.04f))
            }
            2 -> {
                rotate(12f + t * 10f, Offset(w * 0.85f, h * 0.85f)) {
                    drawRoundRect(tint, Offset(w * 0.6f, h * 0.72f), Size(m * 0.6f, m * 0.6f), CornerRadius(m * 0.12f))
                }
                drawCircle(tint, m * 0.4f, Offset(w * 0.95f, h * 0.05f), style = Stroke(m * 0.06f))
            }
            else -> {
                drawCircle(tint, m * 0.45f, Offset(w * 0.0f, h * 0.75f + t * 20f))
                drawPath(bubble(Offset(w * 0.62f, h * 0.06f - t * 12f), Size(m * 0.5f, m * 0.34f), m * 0.1f), tint)
            }
        }
    }
}

/** Appears a moment after the page, rising into place; [order] staggers the parts of a page. */
@Composable
internal fun Rise(order: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(RISE_START_MS + order * RISE_STEP_MS)
        shown.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow))
    }
    Box(
        modifier.graphicsLayer {
            alpha = shown.value.coerceIn(0f, 1f)
            translationY = (1f - shown.value) * 40.dp.toPx()
        },
    ) { content() }
}

/** Counts from 0 up to [target] once the page shows. */
@Composable
internal fun rememberCountUp(target: Long): Long {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(target) {
        delay(RISE_START_MS + RISE_STEP_MS)
        progress.animateTo(1f, tween(1_400, easing = FastOutSlowInEasing))
    }
    return (target * progress.value.toDouble()).roundToLong()
}

/** One line of huge type that shrinks to fit the width, for the number a page is about. */
@Composable
internal fun Headline(text: String, color: Color, maxSize: TextUnit = 120.sp, minSize: TextUnit = 32.sp) {
    BasicText(
        text,
        style = TextStyle(color = color, fontWeight = FontWeight.Black, letterSpacing = (-0.03).em, lineHeight = 1.05.em),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = minSize, maxFontSize = maxSize),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Kicker(text: String, color: Color) {
    Text(text, color = color, style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp))
}

@Composable
private fun Line(text: String, color: Color) {
    Text(text, color = color, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp))
}

/** A short fact on a pill of the accent colour, like a sticker on the poster. */
@Composable
private fun Sticker(text: String, background: Color, content: Color) {
    Text(
        text,
        color = content,
        style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

@Composable
private fun Fact(label: String, value: String, detail: String?, colors: RecapColors) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.content.copy(alpha = 0.12f))
            .padding(16.dp),
    ) {
        Text(label, color = colors.content.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(value, color = colors.accent, fontSize = 20.sp, fontWeight = FontWeight.Black, lineHeight = 24.sp)
        if (detail != null) Text(detail, color = colors.content, fontSize = 13.sp)
    }
}

@Composable
private fun RankRow(rank: Int, share: Float, colors: RecapColors, leading: @Composable () -> Unit, name: String, count: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(rank.toString(), color = colors.content.copy(alpha = 0.6f), fontSize = 20.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(28.dp))
        leading()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = colors.content, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(count, color = colors.content.copy(alpha = 0.8f), fontSize = 15.sp)
            }
            Spacer(Modifier.height(6.dp))
            val grown = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                delay(RISE_START_MS + rank * RISE_STEP_MS)
                grown.animateTo(share.coerceIn(0.04f, 1f), tween(900, easing = FastOutSlowInEasing))
            }
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(colors.content.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(grown.value).clip(CircleShape).background(colors.accent))
            }
        }
    }
}

/** Bars growing from the bottom, the [highlight] one in the accent colour. */
@Composable
private fun Bars(values: List<Long>, highlight: Int, colors: RecapColors, height: Dp) {
    val grown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(RISE_START_MS + RISE_STEP_MS * 2)
        grown.animateTo(1f, tween(1_000, easing = FastOutSlowInEasing))
    }
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.fillMaxWidth().height(height),
    ) {
        values.forEachIndexed { i, v ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight((v.toFloat() / max * grown.value).coerceAtLeast(0.03f))
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .background(if (i == highlight) colors.accent else colors.content.copy(alpha = 0.35f)),
            )
        }
    }
}

/**
 * The whole year as small squares, one column per week like a contribution graph: active days in
 * the accent colour, darker the busier, and the longest streak in the text colour.
 */
@Composable
private fun YearGrid(year: Int, stats: YearStats, streak: Streak, colors: RecapColors, locale: Locale) {
    val firstDay = WeekFields.of(locale).firstDayOfWeek
    val jan1 = LocalDate.of(year, 1, 1)
    val lead = Math.floorMod(jan1.dayOfWeek.value - firstDay.value, 7)
    val days = jan1.lengthOfYear()
    val weeks = (lead + days + 6) / 7
    val max = (stats.sentPerDay.values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val revealed = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(RISE_START_MS + RISE_STEP_MS * 4)
        revealed.animateTo(1f, tween(1_200, easing = FastOutSlowInEasing))
    }
    Canvas(Modifier.fillMaxWidth().aspectRatio(weeks / 7f)) {
        val cell = size.width / weeks
        val gap = cell * 0.18f
        val shownWeeks = weeks * revealed.value
        for (d in 0 until days) {
            val date = jan1.plusDays(d.toLong())
            val slot = lead + d
            val week = slot / 7
            if (week > shownWeeks) break
            val row = slot % 7
            val color = dayColor(stats.sentPerDay[date.toString()], max, inStreak = !date.isBefore(streak.from) && !date.isAfter(streak.to), colors)
            drawRoundRect(
                color,
                topLeft = Offset(week * cell + gap / 2, row * cell + gap / 2),
                size = Size(cell - gap, cell - gap),
                cornerRadius = CornerRadius(cell * 0.2f),
            )
        }
    }
}

/**
 * A day's square: faint while inactive, the content colour in the longest streak, otherwise the
 * accent colour, stronger the more of the busiest day's [max] messages it had.
 */
internal fun dayColor(sent: Long?, max: Long, inStreak: Boolean, colors: RecapColors): Color = when {
    sent == null -> colors.content.copy(alpha = 0.1f)
    inStreak -> colors.content
    // Active, but from before days were counted one by one.
    sent == 0L -> colors.accent.copy(alpha = 0.55f)
    else -> colors.accent.copy(alpha = 0.45f + 0.55f * sqrt(sent.toFloat() / max))
}

/** The stored address is the size the chat draws; the emote of the year shows larger. */
private fun largerEmote(url: String): String = url
    .replace("/2x.", "/3x.")
    .replace("/default/dark/2.0", "/default/dark/3.0")
    .replace(FFZ_SIZE, "$1/4")

private val FFZ_SIZE = Regex("""(frankerfacez\.com/emote/\w+)/2$""")

private const val RISE_START_MS = 180L
private const val RISE_STEP_MS = 110L

/** Per list on the mentions page; the stats page has the longer lists. */
internal const val PEOPLE_SHOWN = 3

/** For plurals: the count clamped into an Int, which is all Android's plural rules take. */
internal fun Long.toQuantity(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

internal fun formatCount(n: Long): String = NumberFormat.getIntegerInstance().format(n)

/** "1.2 million", for a number nobody reads to the last digit; smaller ones read better in full. */
private fun formatCompact(n: Long): String =
    if (n < 1_000_000) formatCount(n)
    else CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.LONG).format(n)

private fun formatHour(hour: Int, locale: Locale): String =
    LocalTime.of(hour, 0).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))

/** [skeleton] as Android's best pattern for the locale, e.g. "dMMMM" gives "14 March" or "March 14". */
private fun formatDay(day: LocalDate, locale: Locale, skeleton: String): String =
    day.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale))

@Composable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]
