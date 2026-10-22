package io.github.gaiser147.claudeoverlay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The desktop overlay's palette (config.THEMES), styled after the Claude desktop app. */
@Immutable
data class OverlayColors(
    val bg: Color, val field: Color, val userCard: Color, val text: Color, val muted: Color,
    val faint: Color, val accent: Color, val accentHi: Color, val onAccent: Color,
    val border: Color, val toolBg: Color, val err: Color,
)

val LightOverlayColors = OverlayColors(
    bg = Color(0xFFFAF9F5), field = Color(0xFFFFFFFF), userCard = Color(0xFFEFEBE1),
    text = Color(0xFF28261F), muted = Color(0xFF73706A), faint = Color(0xFFA9A59B),
    accent = Color(0xFFD97757), accentHi = Color(0xFFC25E40), onAccent = Color(0xFFFFFFFF),
    border = Color(0xFFE6E2D8), toolBg = Color(0xFFF2EFE7), err = Color(0xFFB4413A),
)

val DarkOverlayColors = OverlayColors(
    bg = Color(0xFF262624), field = Color(0xFF1F1E1D), userCard = Color(0xFF34332F),
    text = Color(0xFFECEAE3), muted = Color(0xFF9B978D), faint = Color(0xFF6F6C64),
    accent = Color(0xFFD97757), accentHi = Color(0xFFE68A6C), onAccent = Color(0xFFFFFFFF),
    border = Color(0xFF3A3934), toolBg = Color(0xFF2E2D2A), err = Color(0xFFE0897D),
)

val LocalOverlayColors = staticCompositionLocalOf { LightOverlayColors }

@Composable
fun ClaudeOverlayTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkOverlayColors else LightOverlayColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.text,
            surface = c.bg, onSurface = c.text, surfaceVariant = c.toolBg, onSurfaceVariant = c.muted,
            outline = c.border, error = c.err, surfaceContainer = c.field, surfaceContainerHigh = c.userCard,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.text,
            surface = c.bg, onSurface = c.text, surfaceVariant = c.toolBg, onSurfaceVariant = c.muted,
            outline = c.border, error = c.err, surfaceContainer = c.field, surfaceContainerHigh = c.userCard,
        )
    }
    CompositionLocalProvider(LocalOverlayColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
