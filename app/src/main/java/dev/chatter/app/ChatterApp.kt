package dev.chatter.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dev.chatter.app.crash.CrashLog
import dev.chatter.app.crash.DeviceInfo

class ChatterApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // First of all, so that a crash while everything else is built is written down too.
        val crashes = CrashLog(filesDir.resolve("crashes"), DeviceInfo.current(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
        crashes.install()
        container = AppContainer(this, crashes)
        container.start()
    }

    /**
     * Android wants memory back from a process it may end next. The statistics wait up to ten
     * minutes between saves in the background, and this is the last good moment for them.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) container.stats.saveNow()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader
}
