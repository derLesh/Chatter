package me.lesh.chatter.debug

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.PowerManager
import java.lang.reflect.Modifier

/** Log lines about the app itself: its screens, the phone's settings, the network, settings changes. */
object AppWatch {
    private const val TAG = "App"

    /** Every activity's life, and every change to the configuration (turning, dark mode, size). */
    fun install(app: Application) {
        var last = Configuration(app.resources.configuration)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, saved: Bundle?) =
                step(activity, if (saved == null) "created" else "created again from saved state")
            override fun onActivityStarted(activity: Activity) = step(activity, "visible")
            override fun onActivityResumed(activity: Activity) = step(activity, "in front")
            override fun onActivityPaused(activity: Activity) = step(activity, "no longer in front")
            override fun onActivityStopped(activity: Activity) = step(activity, "hidden")
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) =
                step(activity, if (activity.isChangingConfigurations) "closed to be built again" else "closed")
        })
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onConfigurationChanged(config: Configuration) {
                val changes = configChanges(last, config)
                if (changes.isNotEmpty()) AppLog.i(TAG, "Configuration: ${changes.joinToString()}")
                last = Configuration(config)
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = AppLog.w(TAG, "Android is low on memory")

            override fun onTrimMemory(level: Int) = AppLog.d(TAG, "Android asks to free memory: ${trimLevel(level)}, ${memory()}")
        })
    }

    private fun step(activity: Activity, what: String) =
        AppLog.d(TAG, "${activity.javaClass.simpleName} $what${if (activity.isInMultiWindowMode) " (split screen)" else ""}")

    /** Internal for tests. */
    internal fun configChanges(old: Configuration, new: Configuration): List<String> = buildList {
        if (old.orientation != new.orientation) add(if (new.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait")
        val night = Configuration.UI_MODE_NIGHT_MASK
        if (old.uiMode and night != new.uiMode and night) add(if (new.uiMode and night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light")
        if (old.screenWidthDp != new.screenWidthDp || old.screenHeightDp != new.screenHeightDp) {
            add("${new.screenWidthDp}×${new.screenHeightDp} dp")
        }
        if (old.fontScale != new.fontScale) add("text ×${new.fontScale}")
        if (old.densityDpi != new.densityDpi) add("${new.densityDpi} dpi")
        if (old.locales != new.locales) add("language ${new.locales[0].language}")
        if (old.keyboard != new.keyboard || old.hardKeyboardHidden != new.hardKeyboardHidden) {
            add(if (new.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO) "keyboard attached" else "no keyboard")
        }
    }

    private fun trimLevel(level: Int) = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "app hidden"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "in the background"
        else -> "level $level"
    }

    /** How much memory the app holds right now. */
    fun memory(): String {
        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576
        return "using $used of ${runtime.maxMemory() / 1_048_576} MB"
    }

    /**
     * What the phone and the app are doing right now: "app in the background, screen off, Doze,
     * battery saver off, process: foreground service, network: Wi-Fi, internet, …".
     */
    fun situation(context: Context, appVisible: Boolean): String {
        val power = context.getSystemService(PowerManager::class.java)
        val activities = context.getSystemService(ActivityManager::class.java)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val process = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
        return listOfNotNull(
            if (appVisible) "app on screen" else "app in the background",
            if (power.isInteractive) "screen on" else "screen off",
            "Doze".takeIf { power.isDeviceIdleMode },
            "light Doze".takeIf { power.isDeviceLightIdleMode },
            "battery saver".takeIf { power.isPowerSaveMode },
            "background restricted".takeIf { activities.isBackgroundRestricted },
            "process: ${importance(process.importance)}",
            memory(),
            "network: ${network(connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities))}",
        ).joinToString()
    }

    private fun importance(value: Int) = when (value) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "in front"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground service"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "top, sleeping"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached, may be ended any time"
        else -> "importance $value"
    }

    /** "Wi-Fi, internet, unmetered, 120/20 Mbit/s". */
    fun network(caps: NetworkCapabilities?): String {
        caps ?: return "none"
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "other"
        }
        return listOfNotNull(
            kind,
            "VPN".takeIf { kind != "VPN" && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) },
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "internet" else "no internet yet",
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "unmetered" else "metered",
            "captive portal".takeIf { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) },
            "data saver".takeIf { !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) },
            "${caps.linkDownstreamBandwidthKbps / 1000}/${caps.linkUpstreamBandwidthKbps / 1000} Mbit/s",
        ).joinToString()
    }

    /**
     * What changed between two copies of a settings class, one line per field. A switch or number
     * shows its value in both logs; anything else, which can hold a name or a word, only in the
     * full one. Internal for tests.
     */
    internal fun <T : Any> changes(old: T, new: T): List<String> =
        old.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.mapNotNull { field ->
            field.isAccessible = true
            val before = field.get(old)
            val after = field.get(new)
            if (before == after) return@mapNotNull null
            val shown = when (after) {
                is Boolean, is Number, is Enum<*> -> " = $after"
                else -> AppLog.hidden(" = ${after.toString().take(200)}", " changed")
            }
            "${field.name}$shown"
        }
}
