package niel.kro.penik.ui.theme

import android.content.Context
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

data class ThemeTransitionEvent(
    val origin: Offset,
    val isLight: Boolean,
    val id: Long
)

// ThemeManager persists the user's light/dark choice and exposes it as a
// StateFlow so the root composable can react to changes at runtime.
object ThemeManager {
    private const val PREFS = "penik_theme_prefs"
    private const val KEY_LIGHT = "is_light"

    private var appContext: Context? = null
    private val _isLight = MutableStateFlow(false)
    val isLight: StateFlow<Boolean> = _isLight.asStateFlow()

    private val eventIdCounter = AtomicLong(0)
    private val _transitionEvent = MutableStateFlow<ThemeTransitionEvent?>(null)
    val transitionEvent: StateFlow<ThemeTransitionEvent?> = _transitionEvent.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _isLight.value = prefs.getBoolean(KEY_LIGHT, false)
    }

    fun setLight(light: Boolean, origin: Offset = Offset.Unspecified) {
        if (_isLight.value == light) return
        _isLight.value = light
        _transitionEvent.value = ThemeTransitionEvent(origin, light, eventIdCounter.incrementAndGet())
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_LIGHT, light)
            ?.apply()
    }

    fun toggle(origin: Offset = Offset.Unspecified) = setLight(!_isLight.value, origin)
}
