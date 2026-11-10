package me.lesh.chatter.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.lesh.chatter.R
import me.lesh.chatter.chat.ChatItem
import me.lesh.chatter.chat.MessageBody
import me.lesh.chatter.chat.MessageKind
import me.lesh.chatter.chat.Segment
import me.lesh.chatter.chat.chatterName
import me.lesh.chatter.emotes.Emote
import me.lesh.chatter.emotes.EmoteProvider
import me.lesh.chatter.emotes.twitchEmoteUrl
import me.lesh.chatter.settings.EmoteSize
import me.lesh.chatter.settings.LineSpacing
import me.lesh.chatter.settings.Settings
import me.lesh.chatter.settings.ThemeMode
import me.lesh.chatter.settings.NameStyle
import me.lesh.chatter.settings.TimestampFormat
import me.lesh.chatter.ui.MainViewModel
import me.lesh.chatter.ui.SettingsSubPage
import me.lesh.chatter.ui.chat.MessageRow
import me.lesh.chatter.ui.chat.rememberChatStyle
import me.lesh.chatter.ui.theme.NameColorPalette
import me.lesh.chatter.ui.theme.isAppInDarkTheme
import me.lesh.chatter.ui.theme.readableNameColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun AppearancePage(settings: Settings, vm: MainViewModel, open: (SettingsSubPage) -> Unit) {
    val dark = isAppInDarkTheme()
    SettingsGroup(R.string.settings_group_colors) {
        item(R.string.settings_theme) { ThemeModeItem(settings.themeMode, vm::setThemeMode, R.string.settings_theme) }
        item(R.string.settings_dynamic_color) { SwitchItem(R.string.settings_dynamic_color, settings.dynamicColor, vm::setDynamicColor, R.string.settings_dynamic_color_hint) }
        // Only while the app is dark (chosen, or dark via System); a light theme has no black.
        if (dark) {
            item(R.string.settings_pure_black) {
                SwitchItem(R.string.settings_pure_black, settings.pureBlack, vm::setPureBlack, R.string.settings_pure_black_hint)
            }
        }
        item(R.string.settings_highlight_color) { HighlightColorPicker(settings.highlightColor, vm::setHighlightColor) }
        item(R.string.settings_name_colors) { NameColorPicker(settings.nameColors, vm::setNameColors) }
    }
    SettingsGroup {
        item(R.string.settings_app_icon) { AppIconItem(vm) { open(SettingsSubPage.AppIcons) } }
    }

    // Everything that changes how the chat looks, under one preview that shows it.
    var fontSize by remember(settings.fontSize) { mutableFloatStateOf(settings.fontSize) }
    SettingsGroup(R.string.settings_group_text) {
        item {
            val messages = previewMessages()
            Box(Modifier.padding(16.dp)) { ChatPreview(messages, settings, fontSize, vm) }
        }
        item(R.string.settings_font_size) { TextSizeSlider(fontSize, { fontSize = it }, vm) }
        item(R.string.settings_line_spacing) {
            SegmentedItem(R.string.settings_line_spacing, settings.lineSpacing, LINE_SPACINGS, vm::setLineSpacing)
        }
        item(R.string.settings_emote_size) {
            SegmentedItem(R.string.settings_emote_size, settings.emoteSize, EMOTE_SIZES, vm::setEmoteSize)
        }
        item(R.string.settings_message_dividers) {
            SwitchItem(
                R.string.settings_message_dividers, settings.messageDividers,
                vm::setMessageDividers, R.string.settings_message_dividers_hint,
            )
        }
        item(R.string.settings_alternate_background) {
            SwitchItem(
                R.string.settings_alternate_background, settings.alternateBackground,
                vm::setAlternateBackground, R.string.settings_alternate_background_hint,
            )
        }
        item(R.string.settings_timestamps) { TimestampPicker(settings.timestamps, vm::setTimestamps) }
        item(R.string.settings_name_style) { NameStylePicker(settings.nameStyle, vm::setNameStyle) }
    }
}

/** Three one-word options, spelled out as buttons rather than a sheet. [title] goes above them. */
@Composable
internal fun ThemeModeItem(selected: ThemeMode, onSelect: (ThemeMode) -> Unit, title: Int? = null) {
    ListItem(
        headlineContent = {
            Column {
                title?.let { Text(stringResource(it), Modifier.padding(bottom = 8.dp)) }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    THEME_MODES.forEachIndexed { i, (mode, label) ->
                        SegmentedButton(
                            selected = selected == mode,
                            onClick = { onSelect(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, THEME_MODES.size),
                        ) { Text(stringResource(label)) }
                    }
                }
            }
        },
        colors = transparentItem(),
    )
}

/** Chat text size with a live preview, for the onboarding. Two lines, so the alternating background shows. */
@Composable
internal fun TextSizeItem(settings: Settings, vm: MainViewModel) {
    var size by remember(settings.fontSize) { mutableFloatStateOf(settings.fontSize) }
    val messages = previewMessages().take(2)
    TextSizeSlider(size, { size = it }, vm) {
        ChatPreview(messages, settings, size, vm, Modifier.padding(top = 4.dp))
    }
}

/**
 * The text size slider. [size] follows the finger and is saved on release, so a preview can follow
 * it while dragging.
 */
@Composable
private fun TextSizeSlider(size: Float, onSize: (Float) -> Unit, vm: MainViewModel, preview: @Composable () -> Unit = {}) {
    SliderItem(
        title = R.string.settings_font_size,
        valueLabel = size.roundToInt().toString(),
        value = size,
        onChange = onSize,
        onDone = { vm.setFontSize(size.roundToInt().toFloat()) },
        range = 10f..24f,
        steps = 13,
        below = preview,
    )
}

/** Made-up messages: a short one, one with an emote and one long enough to wrap. */
@Composable
private fun previewMessages(): List<ChatItem> {
    val short = stringResource(R.string.settings_text_size_sample)
    val emote = stringResource(R.string.settings_preview_emote)
    val long = stringResource(R.string.settings_preview_long)
    return remember(short, emote, long) {
        val now = System.currentTimeMillis()
        val kappa = Emote("Kappa", "25", twitchEmoteUrl("25"), EmoteProvider.Twitch)
        fun message(i: Int, name: String, color: Int, body: List<Segment>, text: String) = ChatItem(
            id = "preview$i", channel = "", kind = MessageKind.Chat, timestamp = now,
            login = name.lowercase(), displayName = name, color = color,
            body = MessageBody.of(body), text = text,
            // As the chat alternates them, so the alternating background shows.
            alternate = i % 2 == 1,
        )
        listOf(
            message(0, "Chatter", 0xFF1E90FF.toInt(), listOf(Segment.Text(short)), short),
            message(1, "NightOwl", 0xFF9ACD32.toInt(), listOf(Segment.Text("$emote "), Segment.EmoteSeg(kappa)), "$emote Kappa"),
            message(2, "Lurker", 0xFFFF7F50.toInt(), listOf(Segment.Text(long)), long),
        )
    }
}

/** [messages] drawn the way the chat would draw them with [settings], at text size [fontSize]. */
@Composable
private fun ChatPreview(messages: List<ChatItem>, settings: Settings, fontSize: Float, vm: MainViewModel, modifier: Modifier = Modifier) {
    val style = rememberChatStyle(settings, emptyMap()).copy(fontSize = fontSize.roundToInt().toFloat(), haptics = false)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        // No padding around the rows, so their backgrounds fill the preview.
        Column { messages.forEach { MessageRow(it, style, vm.imageLoader, onGesture = null) } }
    }
}

/** A choice of two or three short options, spelled out as buttons under the title. */
@Composable
internal fun <T> SegmentedItem(title: Int, selected: T, options: List<Pair<T, Int>>, onSelect: (T) -> Unit, hint: Int? = null) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = {
            Column {
                hint?.let { Text(stringResource(it)) }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    options.forEachIndexed { i, (option, label) ->
                        SegmentedButton(
                            selected = selected == option,
                            onClick = { onSelect(option) },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text(stringResource(label)) }
                    }
                }
            }
        },
        colors = transparentItem(),
    )
}

internal val LINE_SPACINGS = listOf(
    LineSpacing.Compact to R.string.line_spacing_compact,
    LineSpacing.Normal to R.string.line_spacing_normal,
    LineSpacing.Roomy to R.string.line_spacing_roomy,
)

private val EMOTE_SIZES = listOf(
    EmoteSize.Normal to R.string.emote_size_normal,
    EmoteSize.Large to R.string.emote_size_large,
)

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

internal val NAME_STYLES = listOf(
    NameStyle.DisplayName to R.string.name_style_display,
    NameStyle.Login to R.string.name_style_login,
    NameStyle.OtherScripts to R.string.name_style_other_scripts,
    NameStyle.Both to R.string.name_style_both,
)

/** How names are shown; each option shows a Korean and a Latin name that way. */
@Composable
private fun NameStylePicker(selected: NameStyle, onSelect: (NameStyle) -> Unit) {
    ChoiceItem(
        title = R.string.settings_name_style,
        value = selected,
        options = NAME_STYLES.map { it.first },
        label = { style -> stringResource(NAME_STYLES.first { it.first == style }.second) },
        onPick = onSelect,
        preview = { style ->
            Text(
                "${chatterName("다니엘", "daniel", style)} · ${chatterName("Forsen", "forsen", style)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/** The current launcher icon; the choice is a page of its own. */
@Composable
private fun AppIconItem(vm: MainViewModel, onOpen: () -> Unit) {
    val current by vm.appIcon.collectAsStateWithLifecycle()
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_app_icon)) },
        supportingContent = { Text(stringResource(current.label)) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppIconPreview(current, size = 40.dp)
                NavChevron()
            }
        },
        colors = transparentItem(),
        modifier = Modifier.tileClickable(onClick = onOpen),
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
