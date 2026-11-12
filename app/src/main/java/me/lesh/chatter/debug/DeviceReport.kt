package me.lesh.chatter.debug

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import me.lesh.chatter.BuildConfig
import me.lesh.chatter.util.shownVersion
import java.util.Locale

/**
 * What the phone and the app are, on top of every log: enough to reproduce a bug, nothing that
 * tells who the phone belongs to. No ids, no account name, no country, no network.
 */
object DeviceReport {
    /** [account] says only how the app is used: "logged in", "guest" or "logged out". */
    fun lines(context: Context, account: String, channels: Int): List<Pair<String, String>> {
        val activities = context.getSystemService(ActivityManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { activities.getMemoryInfo(it) }
        val config = context.resources.configuration
        val display = context.resources.displayMetrics
        return listOf(
            "Chatter" to "$shownVersion (${BuildConfig.VERSION_CODE}), $buildKind",
            "Installed from" to installer(context),
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), patch ${Build.VERSION.SECURITY_PATCH}",
            "Phone" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Processor" to Build.SUPPORTED_ABIS.joinToString(),
            "Memory" to "%.1f GB, app may use %d MB%s".format(
                Locale.ROOT,
                memory.totalMem / 1_073_741_824.0,
                activities.memoryClass,
                if (activities.isLowRamDevice) ", low-memory phone" else "",
            ),
            "Screen" to "${config.screenWidthDp}×${config.screenHeightDp} dp, ${display.densityDpi} dpi, text ×${config.fontScale}",
            "Theme" to if (config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) "dark" else "light",
            "Language" to Locale.getDefault().language,
            "Notifications" to if (NotificationManagerCompat.from(context).areNotificationsEnabled()) "allowed" else "blocked",
            "Battery" to listOfNotNull(
                if (power.isIgnoringBatteryOptimizations(context.packageName)) "not optimized" else "optimized",
                "saver on".takeIf { power.isPowerSaveMode },
                "background restricted".takeIf { activities.isBackgroundRestricted },
                standbyBucket(context),
            ).joinToString(),
            "Account" to account,
            "Channels" to channels.toString(),
            "Running for" to duration(SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()),
        )
    }

    private val buildKind: String get() = when {
        BuildConfig.DEV_BUILD -> "dev build"
        BuildConfig.NIGHTLY -> "nightly"
        else -> "release"
    }

    private fun installer(context: Context): String {
        val name = runCatching {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        }.getOrNull()
        return when (name) {
            null -> "unknown (APK)"
            "com.android.vending" -> "Google Play"
            else -> name
        }
    }

    /** How freely Android lets the app run in the background; "rare" and "restricted" explain missed mentions. */
    private fun standbyBucket(context: Context): String? {
        val bucket = context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket ?: return null
        val name = when (bucket) {
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working set"
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
            UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
            UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "restricted"
            else -> bucket.toString()
        }
        return "standby $name"
    }

    private fun duration(millis: Long): String {
        val minutes = millis / 60_000
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }
}
