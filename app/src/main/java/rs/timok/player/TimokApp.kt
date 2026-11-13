package rs.timok.player

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import rs.timok.player.data.settings.AppPrefs
import rs.timok.player.system.LocaleHelper
import rs.timok.player.work.RefreshWorker
import javax.inject.Inject

@HiltAndroidApp
class TimokApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var okHttp: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)
        RefreshWorker.schedule(this)
    }

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    /** Channel logos are cached on disk so thousands of rows do not refetch them. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { okHttp })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("logos").toOkioPath())
                    .maxSizeBytes(150L * 1024 * 1024)
                    .build()
            }
            .build()
}
