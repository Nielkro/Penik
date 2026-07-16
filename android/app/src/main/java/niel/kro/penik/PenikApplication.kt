package niel.kro.penik

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import niel.kro.penik.domain.WebSocketEventCoordinator
import javax.inject.Inject

@HiltAndroidApp
class PenikApplication : Application() {

    @Inject
    lateinit var webSocketEventCoordinator: WebSocketEventCoordinator

    override fun onCreate() {
        super.onCreate()
        webSocketEventCoordinator.start()
    }
}
