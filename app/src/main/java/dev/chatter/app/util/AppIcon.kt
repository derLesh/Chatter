package dev.chatter.app.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The launcher icon. Each icon is an activity-alias in the manifest; switching enables one alias
 * and disables the others. The package manager is the source of truth, so nothing else is stored.
 */
enum class AppIcon(private val alias: String) {
    Light(".LauncherLight"),
    Dark(".LauncherDark");

    private fun component(context: Context) = ComponentName(context, context.packageName + alias)

    companion object {
        fun current(context: Context): AppIcon {
            val pm = context.packageManager
            return entries.firstOrNull { icon ->
                when (pm.getComponentEnabledSetting(icon.component(context))) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                    // Not changed yet: whatever the manifest says (Light is enabled there).
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == Light
                    else -> false
                }
            } ?: Light
        }

        fun set(context: Context, icon: AppIcon) {
            val pm = context.packageManager
            // Enable the new one first, so there is never a moment without a launcher entry.
            pm.setComponentEnabledSetting(icon.component(context), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            entries.filter { it != icon }.forEach {
                pm.setComponentEnabledSetting(it.component(context), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
