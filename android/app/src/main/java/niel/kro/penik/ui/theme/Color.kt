package niel.kro.penik.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Dark palette (default).
val Background = Color(0xFF0D0D12)
val Panel = Color(0xFF13131A)
val PanelSecondary = Color(0xFF1A1A24)
val InputBg = Color(0xFF1E1E2A)
val Border = Color(0xFF252535)
val BorderLight = Color(0xFF2E2E42)
val Accent = Color(0xFF5B6EF5)
val AccentHover = Color(0xFF4A5DE4)
val TextPrimary = Color(0xFFEEEEF5)
val TextMuted = Color(0xFF7B7B9B)
val TextDim = Color(0xFF44445A)
val SentMessageBg = Color(0xFF2B2D6B)
val SentMessageText = Color(0xFFC8CDFF)
val RecvMessageBg = Color(0xFF1A1A24)
val Danger = Color(0xFFF05A5A)
val Success = Color(0xFF4EC97A)
val Warning = Color(0xFFF5A623)

// AppColors is the palette exposed via CompositionLocal so screens can switch
// between dark and light at runtime. Field names mirror the top-level dark vals.
data class AppColors(
    val background: Color,
    val panel: Color,
    val panelSecondary: Color,
    val inputBg: Color,
    val border: Color,
    val borderLight: Color,
    val accent: Color,
    val accentHover: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val textDim: Color,
    val sentMessageBg: Color,
    val sentMessageText: Color,
    val recvMessageBg: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
    val isLight: Boolean
)

val DarkAppColors = AppColors(
    background = Background,
    panel = Panel,
    panelSecondary = PanelSecondary,
    inputBg = InputBg,
    border = Border,
    borderLight = BorderLight,
    accent = Accent,
    accentHover = AccentHover,
    textPrimary = TextPrimary,
    textMuted = TextMuted,
    textDim = TextDim,
    sentMessageBg = SentMessageBg,
    sentMessageText = SentMessageText,
    recvMessageBg = RecvMessageBg,
    danger = Danger,
    success = Success,
    warning = Warning,
    isLight = false
)

val LightAppColors = AppColors(
    background = Color(0xFFF4F4F8),
    panel = Color(0xFFFFFFFF),
    panelSecondary = Color(0xFFECECF3),
    inputBg = Color(0xFFFFFFFF),
    border = Color(0xFFDCDCE6),
    borderLight = Color(0xFFC9C9D6),
    accent = Color(0xFF4A5DE4),
    accentHover = Color(0xFF3A4DD0),
    textPrimary = Color(0xFF1A1A24),
    textMuted = Color(0xFF6A6A80),
    textDim = Color(0xFFA0A0B4),
    sentMessageBg = Color(0xFF4A5DE4),
    sentMessageText = Color(0xFFFFFFFF),
    recvMessageBg = Color(0xFFECECF3),
    danger = Color(0xFFD83B3B),
    success = Color(0xFF2EA55C),
    warning = Color(0xFFD98A10),
    isLight = true
)

val LocalAppColors = staticCompositionLocalOf { DarkAppColors }
