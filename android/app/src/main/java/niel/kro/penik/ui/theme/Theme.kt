package niel.kro.penik.ui.theme

import android.app.Activity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlin.math.hypot
import kotlin.math.max

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
    val transitionEvent by ThemeManager.transitionEvent.collectAsState()
    val appColors = if (isLight) LightAppColors else DarkAppColors
    val prevColors = if (isLight) DarkAppColors else LightAppColors

    val animatable = remember { Animatable(1f) }
    var activeEventId by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var isAnimating by remember { mutableStateOf(false) }
    var activeOrigin by remember { mutableStateOf(Offset.Unspecified) }

    val currentEvent = transitionEvent
    if (currentEvent != null && currentEvent.id != activeEventId) {
        activeEventId = currentEvent.id
        isAnimating = true
        activeOrigin = currentEvent.origin
    }

    LaunchedEffect(activeEventId) {
        if (activeEventId != 0L) {
            animatable.snapTo(0f)
            animatable.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing)
            )
            isAnimating = false
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val effectiveLight = if (isAnimating) !isLight else isLight
            val bgArgb = (if (isAnimating) prevColors.background else appColors.background).toArgb()
            window.statusBarColor = bgArgb
            window.navigationBarColor = bgArgb
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bgArgb))
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = effectiveLight
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = effectiveLight
        }
    }

    CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(
            colorScheme = if (isLight) LightColorScheme else DarkColorScheme,
            typography = Typography
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                content()

                if (isAnimating) {
                    val progressVal = if (animatable.isRunning) animatable.value else 0f
                    if (progressVal < 1f) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            val origin = if (activeOrigin.isSpecified) {
                                activeOrigin
                            } else {
                                Offset(w - 40.dp.toPx(), 48.dp.toPx())
                            }

                            val maxRadius = hypot(
                                max(origin.x, w - origin.x).toDouble(),
                                max(origin.y, h - origin.y).toDouble()
                            ).toFloat()

                            val currentRadius = maxRadius * progressVal

                            val path = Path().apply {
                                addOval(
                                    Rect(
                                        center = origin,
                                        radius = currentRadius
                                    )
                                )
                            }

                            clipPath(path, clipOp = ClipOp.Difference) {
                                drawRect(color = prevColors.background)
                            }
                        }
                    }
                }
            }
        }
    }
}
