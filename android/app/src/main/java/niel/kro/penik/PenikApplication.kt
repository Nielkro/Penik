package niel.kro.penik

import android.app.Activity
import android.app.Application
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import dagger.hilt.android.HiltAndroidApp
import niel.kro.penik.domain.WebSocketEventCoordinator
import niel.kro.penik.ui.notification.AppNotificationManager
import javax.inject.Inject

@HiltAndroidApp
class PenikApplication : Application(), ImageLoaderFactory {

    @Inject
    lateinit var webSocketEventCoordinator: WebSocketEventCoordinator

    @Inject
    lateinit var appNotificationManager: AppNotificationManager

    override fun onCreate() {
        super.onCreate()
        niel.kro.penik.data.network.api.ApiConfig.init(this)
        appNotificationManager.createNotificationChannels()
        webSocketEventCoordinator.start()

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
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}
