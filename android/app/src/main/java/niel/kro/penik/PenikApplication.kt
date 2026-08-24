package niel.kro.penik

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import dagger.hilt.android.HiltAndroidApp
import niel.kro.penik.domain.WebSocketEventCoordinator
import javax.inject.Inject

@HiltAndroidApp
class PenikApplication : Application(), ImageLoaderFactory {

    @Inject
    lateinit var webSocketEventCoordinator: WebSocketEventCoordinator

    @Inject
    lateinit var appNotificationManager: niel.kro.penik.ui.notification.AppNotificationManager

    override fun onCreate() {
        super.onCreate()
        appNotificationManager.createNotificationChannels()
        webSocketEventCoordinator.start()
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
