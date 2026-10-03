package dev.chatter.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import dev.chatter.app.settings.Settings
import dev.chatter.app.settings.ThemeMode
import java.util.concurrent.ConcurrentHashMap

val LiveRed = Color(0xFFEB0400)

// Full Material 3 schemes around Twitch purple (hue ~265), used when Material You is off. Every
// surface level is defined so containers are consistent.
private val DarkColors = darkColorScheme(
    primary = Color(0xFFD3BBFF),
    onPrimary = Color(0xFF3F0090),
    primaryContainer = Color(0xFF6A2FD6),
    onPrimaryContainer = Color(0xFFEBDDFF),
    inversePrimary = Color(0xFF7A43E6),
    secondary = Color(0xFFCDC1E0),
    onSecondary = Color(0xFF342B45),
    secondaryContainer = Color(0xFF4B425C),
    onSecondaryContainer = Color(0xFFE9DDFC),
    tertiary = Color(0xFFF2B7C9),
    onTertiary = Color(0xFF4B2533),
    tertiaryContainer = Color(0xFF653B49),
    onTertiaryContainer = Color(0xFFFFD9E3),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE7E0EB),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE7E0EB),
    surfaceVariant = Color(0xFF4A4455),
    onSurfaceVariant = Color(0xFFCCC3D8),
    surfaceTint = Color(0xFFD3BBFF),
    inverseSurface = Color(0xFFE7E0EB),
    inverseOnSurface = Color(0xFF322F36),
    outline = Color(0xFF968DA1),
    outlineVariant = Color(0xFF4A4455),
    surfaceBright = Color(0xFF3B383F),
    surfaceDim = Color(0xFF141218),
    surfaceContainerLowest = Color(0xFF0F0D13),
    surfaceContainerLow = Color(0xFF1D1A21),
    surfaceContainer = Color(0xFF211E25),
    surfaceContainerHigh = Color(0xFF2B2930),
    surfaceContainerHighest = Color(0xFF36333B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF7236D9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEBDDFF),
    onPrimaryContainer = Color(0xFF25005A),
    inversePrimary = Color(0xFFD3BBFF),
    secondary = Color(0xFF645A76),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9DDFC),
    onSecondaryContainer = Color(0xFF1F182F),
    tertiary = Color(0xFF7F525F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD9E3),
    onTertiaryContainer = Color(0xFF32101D),
    background = Color(0xFFFEF7FF),
    onBackground = Color(0xFF1D1A21),
    surface = Color(0xFFFEF7FF),
    onSurface = Color(0xFF1D1A21),
    surfaceVariant = Color(0xFFE8DFF3),
    onSurfaceVariant = Color(0xFF4A4455),
    surfaceTint = Color(0xFF7236D9),
    inverseSurface = Color(0xFF322F36),
    inverseOnSurface = Color(0xFFF5EFF7),
    outline = Color(0xFF7B7486),
    outlineVariant = Color(0xFFCCC3D8),
    surfaceBright = Color(0xFFFEF7FF),
    surfaceDim = Color(0xFFDFD8E1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F1FA),
    surfaceContainer = Color(0xFFF2ECF5),
    surfaceContainerHigh = Color(0xFFECE6EF),
    surfaceContainerHighest = Color(0xFFE7E0E9),
)

@Composable
fun ChatterTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = true,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val context = LocalContext.current
    val base = when {
        // Material You, always available with minSdk 33.
        dynamicColor && dark -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val colors = if (pureBlack && dark) pureBlack(base) else base

    // Status and navigation bar icons follow the app theme, not the system theme.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(colorScheme = colors, content = content)
}

/**
 * [dark] with black behind everything that is on screen for hours (chat and bars), since black OLED
 * pixels are off.
 *
 * Only neutral surfaces change; accents stay, so Material You keeps the wallpaper colors.
 * Containers move one step down the same tonal palette, so tiles, sheets and cards stay lighter
 * than the black and keep their tint.
 */
fun pureBlack(dark: ColorScheme): ColorScheme = dark.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = dark.surface,
    surfaceContainer = dark.surfaceContainerLow,
    surfaceContainerHigh = dark.surfaceContainer,
    surfaceContainerHighest = dark.surfaceContainerHigh,
)

/**
 * Whether this is a [pureBlack] scheme. Material's darkest background is grey, so pure black
 * identifies it without passing a flag around.
 */
val ColorScheme.isPureBlack: Boolean get() = background == Color.Black

/**
 * Title bar and channel tabs: a container color to stand apart from the chat, black on a
 * [pureBlack] scheme since they are on screen as long as the chat.
 */
val ColorScheme.barColor: Color get() = if (isPureBlack) background else surfaceContainer

/** Resolves the mention highlight setting (see [Settings.highlightColor]) to a color. */
fun highlightColor(setting: Int, scheme: ColorScheme): Color = when (setting) {
    Settings.HIGHLIGHT_DEFAULT -> LiveRed
    Settings.HIGHLIGHT_ACCENT -> scheme.primary
    else -> Color(setting)
}

/**
 * Background tint of highlighted messages. Stronger over black, where the same tint looks darker.
 */
fun highlightBackground(setting: Int, scheme: ColorScheme): Color =
    highlightColor(setting, scheme).copy(alpha = if (scheme.isPureBlack) 0.3f else 0.2f)

/** True if the current color scheme is dark, whatever the system setting says. */
@Composable
@ReadOnlyComposable
fun isAppInDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** Twitch's palette for users who never picked a name color. */
private val DefaultNameColors = listOf(
    0xFFFF0000, 0xFF0000FF, 0xFF008000, 0xFFB22222, 0xFFFF7F50, 0xFF9ACD32, 0xFFFF4500, 0xFF2E8B57,
    0xFFDAA520, 0xFFD2691E, 0xFF5F9EA0, 0xFF1E90FF, 0xFFFF69B4, 0xFF8A2BE2, 0xFF00FF7F,
).map { Color(it) }

/**
 * A user's name color: their Twitch color, or one derived from the login if they have none,
 * adjusted by [palette] for readability.
 */
fun readableNameColor(
    argb: Int?,
    login: String?,
    dark: Boolean,
    palette: NameColorPalette = NameColorPalette.HslLuma,
): Color {
    val base = argb?.let { Color(it) } ?: DefaultNameColors[fallbackColorIndex(login, DefaultNameColors.size)]
    return NameColorCache.get(base, dark, palette)
}

/**
 * Which of [size] fallback colors [login] gets. Not `abs(hash) % size`: abs(Int.MIN_VALUE) is
 * negative, and a login with that hash ("polygenelubricants", a valid Twitch name) would crash
 * every chat it writes in.
 */
internal fun fallbackColorIndex(login: String?, size: Int): Int = Math.floorMod((login ?: "").hashCode(), size)

/**
 * Caches [NameColorPalette.adjust] results; each is a twelve-step search with color space
 * conversions, and message rows rebuild their text whenever they scroll back into view. Keyed by
 * color, not user: users without a color share a palette of fifteen.
 */
private object NameColorCache {
    private val entries = ConcurrentHashMap<Long, Color>()

    fun get(base: Color, dark: Boolean, palette: NameColorPalette): Color {
        val key = (base.toArgb().toLong() and 0xFFFFFFFFL) or
            (palette.ordinal.toLong() shl 32) or
            (if (dark) 1L shl 40 else 0L)
        entries[key]?.let { return it }
        // Bounded, since a channel full of custom colors could grow it forever; refilling costs one
        // solve per visible color.
        if (entries.size >= MAX_ENTRIES) entries.clear()
        return palette.adjust(base, dark).also { entries[key] = it }
    }

    private const val MAX_ENTRIES = 4096
}
