package dev.chatter.app.ui.settings

import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chatter.app.R
import dev.chatter.app.settings.Settings
import dev.chatter.app.ui.MainViewModel
import dev.chatter.app.util.AppIcon
import dev.chatter.app.util.AppIconGroup
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What else the search finds the app icon by: the groups, the icons' names and the switch. */
internal val APP_ICON_TERMS: List<Int> =
    AppIconGroup.entries.map { it.title } + AppIcon.entries.filter { it.listed }.map { it.label } +
        R.string.settings_seasonal_icons

/** Every app icon this build offers, by group; a seasonal one outside its days is shown locked. */
@Composable
internal fun AppIconsPage(settings: Settings, vm: MainViewModel) {
    val current by vm.appIcon.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    val dayFormat = remember {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMM"))
    }
    AppIconGroup.entries.forEach { group ->
        val icons = AppIcon.entries.filter { it.group == group && it.listed }
        if (icons.isEmpty()) return@forEach
        SettingsGroup(group.title) {
            if (group == AppIconGroup.Seasons) {
                item(R.string.settings_seasonal_icons) {
                    SwitchItem(R.string.settings_seasonal_icons, settings.seasonalAppIcons, vm::setSeasonalAppIcons, R.string.settings_seasonal_icons_hint)
                }
            }
            item {
                BoxWithConstraints(Modifier.padding(12.dp)) {
                    // As many columns as fit, sharing the width, so the rows line up.
                    val columns = (maxWidth / CHOICE_WIDTH).toInt().coerceAtLeast(1)
                    val width = maxWidth / columns
                    FlowRow(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        icons.forEach { icon ->
                            val open = icon.isOpen(today)
                            val from = icon.season?.takeUnless { open }?.nextStart(today)?.format(dayFormat)
                            AppIconChoice(icon, width, selected = icon == current, open = open, from = from) {
                                vm.pickAppIcon(icon)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIconChoice(icon: AppIcon, width: Dp, selected: Boolean, open: Boolean, from: String?, onPick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(width)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = open && !selected, onClick = onPick)
            .padding(vertical = 8.dp),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            AppIconPreview(
                icon,
                size = 64.dp,
                selected = selected,
                modifier = Modifier.alpha(if (open) 1f else LOCKED_ALPHA),
            )
            if (!open) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(4.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(icon.label),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        if (from != null) {
            Text(
                stringResource(R.string.app_icon_from, from),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/** The icon as the launcher draws it, in a circle; [selected] rings it in the theme color. */
@Composable
internal fun AppIconPreview(icon: AppIcon, size: Dp, modifier: Modifier = Modifier, selected: Boolean = false) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .border(
                if (selected) 3.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                CircleShape,
            ),
    ) {
        // The launcher shows the middle 72 of the 108dp background, and the glyph below is drawn
        // 1.5 times the launcher's size as well.
        Image(painterResource(icon.background), contentDescription = null, modifier = Modifier.matchParentSize().scale(1.5f))
        Icon(
            painterResource(R.drawable.ic_chatter_monochrome),
            contentDescription = null,
            tint = if (icon.blackGlyph) Color(0xFF111111) else Color.White,
            modifier = Modifier.size(size),
        )
    }
}

private val CHOICE_WIDTH = 80.dp
private const val LOCKED_ALPHA = 0.4f
