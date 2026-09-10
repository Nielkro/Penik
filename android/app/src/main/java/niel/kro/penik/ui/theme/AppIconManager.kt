package niel.kro.penik.ui.theme

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import niel.kro.penik.R

enum class AppVariant(val id: String, val displayName: String, val iconRes: Int) {
    PENIK("penik", "Penik", R.mipmap.ic_launcher),
    REPIK("repik", "Репик", R.drawable.ic_logo_repik);

    companion object {
        fun fromId(id: String?): AppVariant {
            return entries.firstOrNull { it.id == id } ?: PENIK
        }
    }
}

/**
 * Manages the active application identity, dynamic launcher icon, and app title.
 * Switches between "Penik" (default) and "Репик" using Android activity-alias.
 */
object AppIconManager {
    private const val PREFS = "penik_app_variant_prefs"
    private const val KEY_VARIANT = "active_variant"

    private const val ALIAS_PENIK = "niel.kro.penik.MainActivity"
    private const val ALIAS_REPIK = "niel.kro.penik.MainActivityRepik"

    private var appContext: Context? = null
    private val _currentVariant = MutableStateFlow(AppVariant.PENIK)
    val currentVariant: StateFlow<AppVariant> = _currentVariant.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedId = prefs.getString(KEY_VARIANT, AppVariant.PENIK.id)
        val variant = AppVariant.fromId(savedId)
        _currentVariant.value = variant
    }

    fun setVariant(variant: AppVariant, context: Context) {
        if (_currentVariant.value == variant) return

        _currentVariant.value = variant
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VARIANT, variant.id)
            .apply()

        applyLauncherAlias(context.applicationContext, variant)
    }

    private fun applyLauncherAlias(context: Context, variant: AppVariant) {
        try {
            val pm = context.packageManager
            val penikComponent = ComponentName(context.packageName, ALIAS_PENIK)
            val repikComponent = ComponentName(context.packageName, ALIAS_REPIK)

            val (penikState, repikState) = when (variant) {
                AppVariant.PENIK -> Pair(
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                )
                AppVariant.REPIK -> Pair(
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                )
            }

            pm.setComponentEnabledSetting(
                penikComponent,
                penikState,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                repikComponent,
                repikState,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {
            // In case a restricted device launcher disallows component toggling
        }
    }
}
