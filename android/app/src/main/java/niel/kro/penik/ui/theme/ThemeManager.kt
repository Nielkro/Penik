package niel.kro.penik.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ThemeManager persists the user's light/dark choice and exposes it as a
// StateFlow so the root composable can react to changes at runtime.
object ThemeManager {
    private const val PREFS = "penik_theme_prefs"
    private const val KEY_LIGHT = "is_light"

    private var appContext: Context? = null
    private val _isLight = MutableStateFlow(false)
    val isLight: StateFlow<Boolean> = _isLight.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _isLight.value = prefs.getBoolean(KEY_LIGHT, false)
    }

    fun setLight(light: Boolean) {
        _isLight.value = light
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_LIGHT, light)
            ?.apply()
    }

    fun toggle() = setLight(!_isLight.value)
}
