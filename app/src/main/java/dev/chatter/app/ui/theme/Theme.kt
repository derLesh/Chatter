package dev.chatter.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs

val TwitchPurple = Color(0xFF9146FF)
val LiveRed = Color(0xFFEB0400)

private val DarkColors = darkColorScheme(
    primary = TwitchPurple,
    onPrimary = Color.White,
    secondary = Color(0xFFBF94FF),
    background = Color(0xFF0E0E10),
    onBackground = Color(0xFFEFEFF1),
    surface = Color(0xFF18181B),
    onSurface = Color(0xFFEFEFF1),
    surfaceVariant = Color(0xFF26262C),
    onSurfaceVariant = Color(0xFFADADB8),
    surfaceContainer = Color(0xFF1F1F23),
    surfaceContainerHigh = Color(0xFF26262C),
)

private val LightColors = lightColorScheme(
    primary = TwitchPurple,
    onPrimary = Color.White,
    secondary = Color(0xFF772CE8),
)

@Composable
fun ChatterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

/** Twitch's palette for users who never picked a name color. */
private val DefaultNameColors = listOf(
    0xFFFF0000, 0xFF0000FF, 0xFF008000, 0xFFB22222, 0xFFFF7F50, 0xFF9ACD32, 0xFFFF4500, 0xFF2E8B57,
    0xFFDAA520, 0xFFD2691E, 0xFF5F9EA0, 0xFF1E90FF, 0xFFFF69B4, 0xFF8A2BE2, 0xFF00FF7F,
).map { Color(it) }

/** Makes user-chosen name colors readable on the current background (e.g. dark blue on black). */
fun readableNameColor(argb: Int?, login: String?, dark: Boolean): Color {
    val base = argb?.let { Color(it) } ?: DefaultNameColors[abs((login ?: "").hashCode()) % DefaultNameColors.size]
    val lum = base.luminance()
    return when {
        dark && lum < 0.2f -> lerp(base, Color.White, 0.5f - lum)
        !dark && lum > 0.5f -> lerp(base, Color.Black, lum - 0.3f)
        else -> base
    }
}
