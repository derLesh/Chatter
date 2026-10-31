package me.lesh.chatter

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import me.lesh.chatter.crash.CrashLog
import me.lesh.chatter.crash.DeviceInfo
import me.lesh.chatter.util.isFirstStart

class ChatterApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // First, so crashes while building the rest are recorded too.
        val crashes = CrashLog(filesDir.resolve("crashes"), DeviceInfo.current(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
        crashes.install()
        container = AppContainer(this, crashes, isFirstStart(this))
        container.start()
    }

    /**
     * Android may end the process next. Background stats and mentions are saved only every few
     * minutes, so save them now.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) {
            container.stats.saveNow()
            container.inbox.saveNow()
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader
}
