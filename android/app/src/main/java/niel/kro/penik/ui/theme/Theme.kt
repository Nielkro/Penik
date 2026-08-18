package niel.kro.penik.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = TextPrimary,
    secondary = PanelSecondary,
    onSecondary = TextPrimary,
    tertiary = AccentHover,
    background = Background,
    onBackground = TextPrimary,
    surface = Panel,
    onSurface = TextPrimary,
    surfaceVariant = PanelSecondary,
    onSurfaceVariant = TextMuted,
    error = Danger,
    onError = TextPrimary,
    outline = Border,
    outlineVariant = BorderLight,
)

private val LightColorScheme = lightColorScheme(
    primary = LightAppColors.accent,
    onPrimary = LightAppColors.sentMessageText,
    secondary = LightAppColors.panelSecondary,
    onSecondary = LightAppColors.textPrimary,
    tertiary = LightAppColors.accentHover,
    background = LightAppColors.background,
    onBackground = LightAppColors.textPrimary,
    surface = LightAppColors.panel,
    onSurface = LightAppColors.textPrimary,
    surfaceVariant = LightAppColors.panelSecondary,
    onSurfaceVariant = LightAppColors.textMuted,
    error = LightAppColors.danger,
    onError = LightAppColors.sentMessageText,
    outline = LightAppColors.border,
    outlineVariant = LightAppColors.borderLight,
)

@Composable
fun PenikTheme(
    isLight: Boolean = false,
    content: @Composable () -> Unit
) {
    val appColors = if (isLight) LightAppColors else DarkAppColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = appColors.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = isLight
        }
    }

    CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(
            colorScheme = if (isLight) LightColorScheme else DarkColorScheme,
            typography = Typography,
            content = content
        )
    }
}
