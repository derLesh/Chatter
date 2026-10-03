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
        // First, so crashes while building the rest are recorded too.
        val crashes = CrashLog(filesDir.resolve("crashes"), DeviceInfo.current(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
        crashes.install()
        container = AppContainer(this, crashes)
        container.start()
    }

    /**
     * Android may end the process next. Background stats are saved only every ten minutes, so save
     * them now.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) container.stats.saveNow()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader
}
