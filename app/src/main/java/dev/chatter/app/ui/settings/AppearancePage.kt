package dev.chatter.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chatter.app.R
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.MessageBody
import dev.chatter.app.chat.MessageKind
import dev.chatter.app.chat.Segment
import dev.chatter.app.settings.Settings
import dev.chatter.app.settings.ThemeMode
import dev.chatter.app.settings.TimestampFormat
import dev.chatter.app.ui.MainViewModel
import dev.chatter.app.ui.chat.ChatStyle
import dev.chatter.app.ui.chat.MessageRow
import dev.chatter.app.ui.theme.NameColorPalette
import dev.chatter.app.ui.theme.highlightBackground
import dev.chatter.app.ui.theme.highlightColor
import dev.chatter.app.ui.theme.isAppInDarkTheme
import dev.chatter.app.ui.theme.readableNameColor
import dev.chatter.app.util.AppIcon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun AppearancePage(settings: Settings, vm: MainViewModel) {
    val dark = isAppInDarkTheme()
    SettingsGroup(R.string.settings_group_colors) {
        item(R.string.settings_theme) {
            ListItem(
                // The buttons are the row: three one-word options are clearer than a sheet, and
                // need no extra label under the "Colors" heading.
                headlineContent = {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        THEME_MODES.forEachIndexed { i, (mode, label) ->
                            SegmentedButton(
                                selected = settings.themeMode == mode,
                                onClick = { vm.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(i, THEME_MODES.size),
                            ) { Text(stringResource(label)) }
                        }
                    }
                },
                colors = transparentItem(),
            )
        }
        item(R.string.settings_dynamic_color) { SwitchItem(R.string.settings_dynamic_color, settings.dynamicColor, vm::setDynamicColor, R.string.settings_dynamic_color_hint) }
        // Only while the app is dark (chosen, or dark via System); a light theme has no black.
        if (dark) {
            item(R.string.settings_pure_black) {
                SwitchItem(R.string.settings_pure_black, settings.pureBlack, vm::setPureBlack, R.string.settings_pure_black_hint)
            }
        }
        item(R.string.settings_highlight_color) { HighlightColorPicker(settings.highlightColor, vm::setHighlightColor) }
        item(R.string.settings_name_colors) { NameColorPicker(settings.nameColors, vm::setNameColors) }
        item(R.string.settings_app_icon) { AppIconPicker() }
    }

    SettingsGroup(R.string.settings_group_text) {
        item(R.string.settings_font_size) { TextSizeItem(settings, vm) }
        item(R.string.settings_timestamps) { TimestampPicker(settings.timestamps, vm::setTimestamps) }
    }

    // How a message is drawn. Emotes have their own category.
    SettingsGroup(R.string.settings_group_messages) {
        item(R.string.settings_alternate_background) {
            SwitchItem(
                R.string.settings_alternate_background, settings.alternateBackground,
                vm::setAlternateBackground, R.string.settings_alternate_background_hint,
            )
        }
        item(R.string.settings_smooth_scrolling) {
            SwitchItem(
                R.string.settings_smooth_scrolling, settings.smoothScrolling,
                vm::setSmoothScrolling, R.string.settings_smooth_scrolling_hint,
            )
        }
        item(R.string.settings_show_deleted) { SwitchItem(R.string.settings_show_deleted, settings.showDeleted, vm::setShowDeleted, R.string.settings_show_deleted_hint) }
        item(R.string.settings_first_messages) {
            SwitchItem(
                R.string.settings_first_messages, settings.highlightFirstMessages,
                vm::setHighlightFirstMessages, R.string.settings_first_messages_hint,
            )
        }
    }

    // The two phone-related settings, in one group.
    SettingsGroup(R.string.settings_group_device) {
        item(R.string.settings_keep_screen_on) {
            SwitchItem(
                R.string.settings_keep_screen_on, settings.keepScreenOn,
                vm::setKeepScreenOn, R.string.settings_keep_screen_on_hint,
            )
        }
        item(R.string.settings_haptics) {
            SwitchItem(
                R.string.settings_haptics, settings.haptics,
                vm::setHaptics, R.string.settings_haptics_hint,
            )
        }
    }
}

/** Chat text size with a live preview line. */
@Composable
private fun TextSizeItem(settings: Settings, vm: MainViewModel) {
    var size by remember(settings.fontSize) { mutableFloatStateOf(settings.fontSize) }
    val scheme = MaterialTheme.colorScheme
    val dark = isAppInDarkTheme()
    val sampleText = stringResource(R.string.settings_text_size_sample)
    val sample = remember(sampleText) {
        ChatItem(
            id = "preview", channel = "", kind = MessageKind.Chat, timestamp = System.currentTimeMillis(),
            login = "chatter", displayName = "Chatter", color = 0xFF1E90FF.toInt(),
            body = MessageBody.of(listOf(Segment.Text(sampleText))), text = sampleText,
        )
    }
    val style = ChatStyle(
        fontSize = size.roundToInt().toFloat(),
        timestamps = settings.timestamps,
        dark = dark,
        secondaryText = scheme.onSurfaceVariant,
        linkColor = scheme.primary,
        mentionBackground = Color.Transparent,
        alternateBackground = null,
        noticeBackground = Color.Transparent,
        firstMessageBackground = null,
        accent = scheme.primary,
        nameColors = settings.nameColors,
        nicknames = emptyMap(),
        haptics = false,
        imageHosts = emptyList(),
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_font_size, size.roundToInt())) },
        supportingContent = {
            Column {
                Slider(
                    value = size,
                    onValueChange = { size = it },
                    onValueChangeFinished = { vm.setFontSize(size.roundToInt().toFloat()) },
                    valueRange = 10f..24f,
                    steps = 13,
                )
                Surface(
                    color = scheme.surface,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Box(Modifier.padding(vertical = 8.dp)) {
                        MessageRow(sample, style, vm.imageLoader, onGesture = null)
                    }
                }
            }
        },
        colors = transparentItem(),
    )
}

internal val THEME_MODES = listOf(
    ThemeMode.System to R.string.theme_system,
    ThemeMode.Light to R.string.theme_light,
    ThemeMode.Dark to R.string.theme_dark,
)

/** Timestamp format; each option shows the current time in that format. */
@Composable
private fun TimestampPicker(selected: TimestampFormat, onSelect: (TimestampFormat) -> Unit) {
    val now = remember { System.currentTimeMillis() }
    ChoiceItem(
        title = R.string.settings_timestamps,
        value = selected,
        options = TimestampFormat.entries,
        // Each option shows the current time in its format.
        label = { format ->
            format.pattern?.let { SimpleDateFormat(it, Locale.getDefault()).format(Date(now)) }
                ?: stringResource(R.string.settings_timestamps_off)
        },
        onPick = onSelect,
    )
}

/** Launcher icon: black C on white or white C on black. */
@Composable
private fun AppIconPicker() {
    val context = LocalContext.current
    var current by remember { mutableStateOf(AppIcon.current(context)) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_app_icon)) },
        supportingContent = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 4.dp)) {
                    listOf(
                        Triple(AppIcon.Light, Color.White, R.string.app_icon_light),
                        Triple(AppIcon.Dark, Color(0xFF111111), R.string.app_icon_dark),
                    ).forEach { (icon, background, label) ->
                        val selected = icon == current
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    if (!selected) {
                                        AppIcon.set(context, icon)
                                        current = icon
                                    }
                                }
                                .padding(8.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(background)
                                    .border(
                                        if (selected) 3.dp else 1.dp,
                                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        CircleShape,
                                    ),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_chatter_monochrome),
                                    contentDescription = null,
                                    tint = if (background == Color.White) Color(0xFF111111) else Color.White,
                                    modifier = Modifier.size(64.dp),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(label),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        },
        colors = transparentItem(),
    )
}

/** Sample chatters' name colors, to compare the palettes. */
private val NAME_COLOR_LABELS = listOf(
    NameColorPalette.None to R.string.name_colors_none,
    NameColorPalette.Twitch to R.string.name_colors_twitch,
    NameColorPalette.HslLuma to R.string.name_colors_hsl_luma,
    NameColorPalette.HslLoop to R.string.name_colors_hsl_loop,
    NameColorPalette.LuvLuma to R.string.name_colors_luv_luma,
    NameColorPalette.RgbLoop to R.string.name_colors_rgb_loop,
)

// Hard cases on purpose: very dark blue, red and green are what needs adjusting.
private val NAME_COLOR_SAMPLES = listOf(
    0xFF0000FF.toInt(), 0xFF8B0000.toInt(), 0xFF006400.toInt(), 0xFFFF69B4.toInt(), 0xFF00FF7F.toInt(),
)

/** How name colors are adjusted; each option previews the same names in its palette. */
@Composable
private fun NameColorPicker(selected: NameColorPalette, onSelect: (NameColorPalette) -> Unit) {
    val dark = isAppInDarkTheme()
    ChoiceItem(
        title = R.string.settings_name_colors,
        value = selected,
        options = NAME_COLOR_LABELS.map { it.first },
        label = { palette -> stringResource(NAME_COLOR_LABELS.first { it.first == palette }.second) },
        onPick = onSelect,
        hint = R.string.settings_name_colors_hint,
        // The same names in every palette, to compare them.
        preview = { palette ->
            NAME_COLOR_SAMPLES.forEach { argb ->
                val color = readableNameColor(argb, null, dark, palette)
                Text(
                    text = stringResource(R.string.settings_name_colors_sample),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        },
    )
}

/** Mention highlight swatches and a preview line. */
@Composable
private fun HighlightColorPicker(selected: Int, onSelect: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val options = listOf(
        Settings.HIGHLIGHT_DEFAULT, Settings.HIGHLIGHT_ACCENT,
        0xFFFF9800.toInt(), 0xFFFFC107.toInt(), 0xFF4CAF50.toInt(), 0xFF00BCD4.toInt(),
        0xFF2196F3.toInt(), 0xFF9C27B0.toInt(), 0xFFE91E63.toInt(),
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_highlight_color)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.settings_highlight_color_hint))
                // A plain scrolling Row: ListItem measures intrinsically, which lazy lists do not
                // support.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()),
                ) {
                    options.forEach { option ->
                        val color = highlightColor(option, scheme)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(color)
                                .then(
                                    if (option == selected) Modifier.border(3.dp, scheme.onSurface, CircleShape) else Modifier
                                )
                                .clickable { onSelect(option) },
                        ) {
                            if (option == selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                                )
                            }
                        }
                    }
                }
                // Preview of a highlighted chat line.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(highlightBackground(selected, scheme))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_highlight_preview_name) + ": ",
                        fontWeight = FontWeight.Bold,
                        color = scheme.primary,
                    )
                    Text(stringResource(R.string.settings_highlight_preview_text), color = scheme.onSurface)
                }
            }
        },
        colors = transparentItem(),
    )
}
