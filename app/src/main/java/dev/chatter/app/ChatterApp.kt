package dev.chatter.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader

class ChatterApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
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
