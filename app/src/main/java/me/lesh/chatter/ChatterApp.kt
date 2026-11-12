package me.lesh.chatter

import android.app.Application
import android.os.Process
import android.os.SystemClock
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import me.lesh.chatter.crash.CrashLog
import me.lesh.chatter.crash.DeviceInfo
import me.lesh.chatter.debug.AppLog
import me.lesh.chatter.debug.AppWatch
import me.lesh.chatter.util.isFirstStart
import me.lesh.chatter.util.shownVersion

class ChatterApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.keepIn(filesDir.resolve("log/log.tsv"))
        // First, so crashes while building the rest are recorded too.
        val crashes = CrashLog(filesDir.resolve("crashes"), DeviceInfo.current(shownVersion, BuildConfig.VERSION_CODE))
        crashes.install()
        val started = SystemClock.elapsedRealtime()
        AppLog.i("App", "Started Chatter $shownVersion (${BuildConfig.VERSION_CODE}), process ${Process.myPid()}, ${AppWatch.memory()}")
        AppWatch.install(this)
        container = AppContainer(this, crashes, isFirstStart(this))
        container.start()
        AppLog.d("App", "Ready after ${SystemClock.elapsedRealtime() - started} ms")
    }

    /**
     * Android may end the process next. Background stats and mentions are saved only every few
     * minutes, so save them now.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) {
            AppLog.flushNow()
            container.stats.saveNow()
            container.inbox.saveNow()
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader
}
