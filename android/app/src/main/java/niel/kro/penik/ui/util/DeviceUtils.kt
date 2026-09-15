package niel.kro.penik.ui.util

import android.os.Build

object DeviceUtils {
    fun getDeviceMarketingName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty().trim()

        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model.replaceFirstChar { it.uppercase() }
        } else if (manufacturer.isNotBlank()) {
            "$manufacturer $model".trim()
        } else {
            model.ifBlank { "Android" }
        }
    }
}
