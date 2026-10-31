package app.vitaflow.com.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class GlassColors(
    val bgTop: Color,
    val bgBottom: Color,
    val blobA: Color,
    val blobB: Color,
    val glassFill: Color,
    val glassHighlight: Color,
    val borderTop: Color,
    val borderBottom: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val danger: Color,
    val success: Color,
    val buttonAlphaTop: Float,
    val buttonAlphaBottom: Float
)

/** Deep navy with a blue glow. */
val DarkGlass = GlassColors(
    bgTop = Color(0xFF050B1E),
    bgBottom = Color(0xFF0B1A3F),
    blobA = Color(0x592B6BFF),
    blobB = Color(0x3818C8FF),
    glassFill = Color(0x14FFFFFF),
    glassHighlight = Color(0x1AFFFFFF),
    borderTop = Color(0x59FFFFFF),
    borderBottom = Color(0x10FFFFFF),
    textPrimary = Color(0xFFEAF1FF),
    textSecondary = Color(0xFF9DB2D6),
    accent = Color(0xFF6AB7FF),
    danger = Color(0xFFFF7A87),
    success = Color(0xFF4ADE9A),
    buttonAlphaTop = 0.60f,
    buttonAlphaBottom = 0.32f
)

/** Bright frosted glass on a pale sky background. */
val LightGlass = GlassColors(
    bgTop = Color(0xFFF3F7FF),
    bgBottom = Color(0xFFDCE8FA),
    blobA = Color(0x737FB2FF),
    blobB = Color(0x66B9D7FF),
    glassFill = Color(0x8CFFFFFF),
    glassHighlight = Color(0x66FFFFFF),
    borderTop = Color(0xF2FFFFFF),
    borderBottom = Color(0x599DB6D9),
    textPrimary = Color(0xFF0E1A33),
    textSecondary = Color(0xFF53698C),
    accent = Color(0xFF1F6FEB),
    danger = Color(0xFFD7263D),
    success = Color(0xFF0E9F6E),
    buttonAlphaTop = 0.95f,
    buttonAlphaBottom = 0.80f
)

val LocalGlass = staticCompositionLocalOf { DarkGlass }

/** true = no blur or glow, for slower phones. */
val LocalReduceEffects = staticCompositionLocalOf { false }

private val VitaTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun VitaFlowTheme(dark: Boolean = isSystemInDarkTheme(), reduceEffects: Boolean = false, content: @Composable () -> Unit) {
    val g = if (dark) DarkGlass else LightGlass
    val scheme = if (dark) {
        darkColorScheme(
            primary = g.accent, onPrimary = Color.White, background = g.bgTop, onBackground = g.textPrimary,
            surface = g.bgBottom, onSurface = g.textPrimary, onSurfaceVariant = g.textSecondary, error = g.danger
        )
    } else {
        lightColorScheme(
            primary = g.accent, onPrimary = Color.White, background = g.bgTop, onBackground = g.textPrimary,
            surface = g.bgBottom, onSurface = g.textPrimary, onSurfaceVariant = g.textSecondary, error = g.danger
        )
    }
    CompositionLocalProvider(LocalGlass provides g, LocalReduceEffects provides reduceEffects) {
        MaterialTheme(colorScheme = scheme, typography = VitaTypography, content = content)
    }
}
