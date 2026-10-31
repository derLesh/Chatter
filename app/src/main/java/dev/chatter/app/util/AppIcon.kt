package dev.chatter.app.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dev.chatter.app.BuildConfig
import dev.chatter.app.R
import java.time.LocalDate

/** Where an icon is listed in the picker. */
enum class AppIconGroup(val title: Int) {
    Colors(R.string.app_icon_group_colors),
    Seasons(R.string.app_icon_group_seasons),
    Build(R.string.app_icon_group_build),
}

/**
 * The launcher icon. Each icon is an activity-alias in the manifest and exactly one is enabled; the
 * package manager is the only place the choice is stored. [background] is the adaptive icon's
 * background, which the picker draws under the glyph; [blackGlyph] for the light ones.
 */
enum class AppIcon(
    private val alias: String,
    val label: Int,
    val background: Int,
    val group: AppIconGroup,
    val blackGlyph: Boolean = false,
    val season: IconSeason? = null,
) {
    Light(".LauncherLight", R.string.app_icon_light, R.drawable.ic_launcher_background_white, AppIconGroup.Colors, blackGlyph = true),
    Dark(".LauncherDark", R.string.app_icon_dark, R.drawable.ic_launcher_background_black, AppIconGroup.Colors),
    Purple(".LauncherPurple", R.string.app_icon_purple, R.drawable.ic_launcher_background_purple, AppIconGroup.Colors),
    Blue(".LauncherBlue", R.string.app_icon_blue, R.drawable.ic_launcher_background_blue, AppIconGroup.Colors),
    Teal(".LauncherTeal", R.string.app_icon_teal, R.drawable.ic_launcher_background_teal, AppIconGroup.Colors),
    Green(".LauncherGreen", R.string.app_icon_green, R.drawable.ic_launcher_background_green, AppIconGroup.Colors),
    Yellow(".LauncherYellow", R.string.app_icon_yellow, R.drawable.ic_launcher_background_yellow, AppIconGroup.Colors, blackGlyph = true),
    Orange(".LauncherOrange", R.string.app_icon_orange, R.drawable.ic_launcher_background_orange, AppIconGroup.Colors),
    Red(".LauncherRed", R.string.app_icon_red, R.drawable.ic_launcher_background_red, AppIconGroup.Colors),
    Pink(".LauncherPink", R.string.app_icon_pink, R.drawable.ic_launcher_background_pink, AppIconGroup.Colors),
    Pride(".LauncherPride", R.string.app_icon_pride, R.drawable.ic_launcher_background_pride, AppIconGroup.Colors),
    Valentine(".LauncherValentine", R.string.app_icon_valentine, R.drawable.ic_launcher_background_valentine, AppIconGroup.Seasons, season = IconSeason.Valentine),
    Easter(".LauncherEaster", R.string.app_icon_easter, R.drawable.ic_launcher_background_easter, AppIconGroup.Seasons, blackGlyph = true, season = IconSeason.Easter),
    Halloween(".LauncherHalloween", R.string.app_icon_halloween, R.drawable.ic_launcher_background_halloween, AppIconGroup.Seasons, blackGlyph = true, season = IconSeason.Halloween),
    Christmas(".LauncherChristmas", R.string.app_icon_christmas, R.drawable.ic_launcher_background_christmas, AppIconGroup.Seasons, season = IconSeason.Christmas),
    NewYear(".LauncherNewYear", R.string.app_icon_new_year, R.drawable.ic_launcher_background_newyear, AppIconGroup.Seasons, season = IconSeason.NewYear),
    Nightly(".LauncherNightly", R.string.app_icon_nightly, R.drawable.ic_launcher_background_night, AppIconGroup.Build),
    DevBuild(".LauncherDevBuild", R.string.app_icon_dev_build, R.drawable.ic_launcher_background_blueprint, AppIconGroup.Build);

    /** Whether the picker lists it: the build icons only in their own build. */
    val listed: Boolean
        get() = when (this) {
            Nightly -> BuildConfig.NIGHTLY
            DevBuild -> BuildConfig.DEV_BUILD
            else -> true
        }

    /** Whether it can be picked on [date]; a seasonal icon only on its days. */
    fun isOpen(date: LocalDate): Boolean = season?.isOn(date) ?: true

    // The manifest names the aliases after the namespace, which the benchmark and profiling builds
    // do not share with their package name.
    private fun component(context: Context) = ComponentName(context.packageName, LAUNCHER_NAMESPACE + alias)

    private fun isEnabled(pm: PackageManager, context: Context): Boolean =
        when (pm.getComponentEnabledSetting(component(context))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            // Never changed: the manifest enables the default.
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> this == default
            else -> false
        }

    companion object {
        /** `namespace` in build.gradle.kts. */
        private const val LAUNCHER_NAMESPACE = "dev.chatter.app"

        /** The icon a fresh install starts with; Nightly's own, so it differs from Chatter's. */
        val default: AppIcon get() = if (BuildConfig.NIGHTLY) Nightly else Light

        fun current(context: Context): AppIcon {
            val pm = context.packageManager
            return entries.firstOrNull { it.isEnabled(pm, context) } ?: default
        }

        fun set(context: Context, icon: AppIcon) {
            val pm = context.packageManager
            // Enable the new one first, so there is always a launcher entry.
            pm.setComponentEnabledSetting(icon.component(context), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            entries.filter { it != icon && it.isEnabled(pm, context) }.forEach {
                pm.setComponentEnabledSetting(it.component(context), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
