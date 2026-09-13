package niel.kro.penik.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NavigationStyle(val displayName: String, val description: String) {
    BOTTOM_BAR("Вкладки снизу", "Панель навигации внизу экрана (по умолчанию)"),
    DRAWER("Боковое меню", "Шторка с профилем и разделами в стиле Telegram")
}

// NavigationStyleManager persists the user's choice of navigation UI (Bottom Bar vs Telegram Drawer)
// and exposes it as a reactive StateFlow.
object NavigationStyleManager {
    private const val PREFS = "penik_navigation_prefs"
    private const val KEY_STYLE = "nav_style"

    private var appContext: Context? = null
    private val _navigationStyle = MutableStateFlow(NavigationStyle.BOTTOM_BAR)
    val navigationStyle: StateFlow<NavigationStyle> = _navigationStyle.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val styleName = prefs.getString(KEY_STYLE, NavigationStyle.BOTTOM_BAR.name)
        _navigationStyle.value = try {
            NavigationStyle.valueOf(styleName ?: NavigationStyle.BOTTOM_BAR.name)
        } catch (_: Exception) {
            NavigationStyle.BOTTOM_BAR
        }
    }

    fun setStyle(style: NavigationStyle) {
        _navigationStyle.value = style
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_STYLE, style.name)
            ?.apply()
    }
}
