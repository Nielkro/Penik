package niel.kro.penik

import android.app.Activity
import android.app.Application
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.launch
import niel.kro.penik.domain.WebSocketEventCoordinator
import niel.kro.penik.ui.notification.AppNotificationManager
import javax.inject.Inject

@HiltAndroidApp
class PenikApplication : Application(), ImageLoaderFactory {

    @Inject
    lateinit var webSocketEventCoordinator: WebSocketEventCoordinator

    @Inject
    lateinit var appNotificationManager: AppNotificationManager

    @Inject
    lateinit var timeSyncManager: niel.kro.penik.data.network.TimeSyncManager

    @Inject
    lateinit var networkMonitor: niel.kro.penik.data.network.NetworkMonitor

    override fun onCreate() {
        super.onCreate()
        niel.kro.penik.data.network.api.ApiConfig.init(this)
        niel.kro.penik.ui.theme.AppIconManager.init(this)
        appNotificationManager.createNotificationChannels()
        webSocketEventCoordinator.start()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            timeSyncManager.syncTime()
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedActivities = 0

            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                AppNotificationManager.isAppInForeground = startedActivities > 0
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                AppNotificationManager.isAppInForeground = startedActivities > 0
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    override fun newImageLoader(): ImageLoader {
        val imageCacheDir = java.io.File(cacheDir, "http_image_cache")
        val okHttpClient = okhttp3.OkHttpClient.Builder()
            .cache(okhttp3.Cache(imageCacheDir, 100L * 1024 * 1024))
            .addInterceptor { chain ->
                var request = chain.request()
                if (!networkMonitor.isConnected()) {
                    request = request.newBuilder()
                        .cacheControl(okhttp3.CacheControl.FORCE_CACHE)
                        .build()
                }
                chain.proceed(request)
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_disk_cache"))
                    .maxSizeBytes(150L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .components {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    add(coil.decode.ImageDecoderDecoder.Factory())
                } else {
                    add(coil.decode.GifDecoder.Factory())
                }
                add(VideoFrameDecoder.Factory())
                add(coil.decode.SvgDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}
