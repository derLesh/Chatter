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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import dev.chatter.app.settings.Settings
import dev.chatter.app.settings.ThemeMode
import kotlin.math.abs

val LiveRed = Color(0xFFEB0400)

// Complete Material 3 schemes built around Twitch purple (tonal palette, hue ~265).
// Used when Material You is off; every surface level is defined so containers look consistent.
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
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val context = LocalContext.current
    val colors = when {
        // Material You (Android 12+, always available with minSdk 33).
        dynamicColor && dark -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }

    // Status/navigation bar icons must follow the app theme, not the system theme.
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

/** Resolves the mention highlight setting (see [Settings.highlightColor]) to a color. */
fun highlightColor(setting: Int, scheme: ColorScheme): Color = when (setting) {
    Settings.HIGHLIGHT_DEFAULT -> LiveRed
    Settings.HIGHLIGHT_ACCENT -> scheme.primary
    else -> Color(setting)
}

/** Background tint of highlighted (mention) messages. */
fun highlightBackground(setting: Int, scheme: ColorScheme): Color = highlightColor(setting, scheme).copy(alpha = 0.2f)

/** True if the current color scheme is dark (whatever the system setting says). */
@Composable
@ReadOnlyComposable
fun isAppInDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** Twitch's palette for users who never picked a name color. */
private val DefaultNameColors = listOf(
    0xFFFF0000, 0xFF0000FF, 0xFF008000, 0xFFB22222, 0xFFFF7F50, 0xFF9ACD32, 0xFFFF4500, 0xFF2E8B57,
    0xFFDAA520, 0xFFD2691E, 0xFF5F9EA0, 0xFF1E90FF, 0xFFFF69B4, 0xFF8A2BE2, 0xFF00FF7F,
).map { Color(it) }

/**
 * The name color to paint a user in: the one they picked on Twitch (or one derived from their
 * login if they never did), run through the [palette] so it stays readable on the background.
 */
fun readableNameColor(
    argb: Int?,
    login: String?,
    dark: Boolean,
    palette: NameColorPalette = NameColorPalette.HslLuma,
): Color {
    val base = argb?.let { Color(it) } ?: DefaultNameColors[abs((login ?: "").hashCode()) % DefaultNameColors.size]
    return palette.adjust(base, dark)
}
