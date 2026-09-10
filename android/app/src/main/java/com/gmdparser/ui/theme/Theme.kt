package com.gmdparser.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.gmdparser.util.AppTheme

private val DarkColorScheme = darkColorScheme(
    primary = MpesaGreen,
    onPrimary = Color(0xFF0F172A),
    primaryContainer = MpesaGreenDark,
    onPrimaryContainer = TextPrimary,
    secondary = AccentCyan,
    onSecondary = Color(0xFF0F172A),
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceCard,
    onSurfaceVariant = TextSecondary,
    outline = TextMuted,
    outlineVariant = DarkSurfaceBorder,
    error = AccentRed,
    onError = TextPrimary
)

private val MidnightColorScheme = darkColorScheme(
    primary = MpesaGreen,
    onPrimary = Color(0xFF060B18),
    primaryContainer = MpesaGreenDark,
    onPrimaryContainer = TextPrimary,
    secondary = AccentPurple,
    onSecondary = TextPrimary,
    background = MidnightBackground,
    onBackground = TextPrimary,
    surface = MidnightSurface,
    onSurface = TextPrimary,
    surfaceVariant = MidnightSurfaceCard,
    onSurfaceVariant = TextSecondary,
    outline = TextMuted,
    outlineVariant = MidnightSurfaceBorder,
    error = AccentRed,
    onError = TextPrimary
)

private val ForestColorScheme = darkColorScheme(
    primary = MpesaGreen,
    onPrimary = Color(0xFF071210),
    primaryContainer = MpesaGreenDark,
    onPrimaryContainer = TextPrimary,
    secondary = AccentCyan,
    onSecondary = Color(0xFF071210),
    background = ForestBackground,
    onBackground = TextPrimary,
    surface = ForestSurface,
    onSurface = TextPrimary,
    surfaceVariant = ForestSurfaceCard,
    onSurfaceVariant = Color(0xFFA2C7BC),
    outline = Color(0xFF5A7F73),
    outlineVariant = ForestSurfaceBorder,
    error = AccentRed,
    onError = TextPrimary
)

private val LightColorScheme = lightColorScheme(
    primary = MpesaGreenDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF065F46),
    secondary = AccentAmber,
    onSecondary = Color.White,
    background = LightBackground,
    onBackground = TextPrimaryLight,
    surface = LightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = LightSurfaceCard,
    onSurfaceVariant = TextSecondaryLight,
    outline = TextMutedLight,
    outlineVariant = LightSurfaceBorder,
    error = AccentRed,
    onError = Color.White
)

// ── Ergonomic Semantic Accessors ─────────────────────────────────────────────
val ColorScheme.surfaceCard: Color
    get() = surfaceVariant

val ColorScheme.surfaceBorder: Color
    get() = outlineVariant

val ColorScheme.textPrimary: Color
    get() = onSurface

val ColorScheme.textSecondary: Color
    get() = onSurfaceVariant

val ColorScheme.textMuted: Color
    get() = outline

@Composable
fun GMDParserTheme(
    content: @Composable () -> Unit
) {
    val theme by ThemeManager.currentTheme.collectAsState()

    val colorScheme = when (theme) {
        AppTheme.DARK     -> DarkColorScheme
        AppTheme.MIDNIGHT -> MidnightColorScheme
        AppTheme.FOREST   -> ForestColorScheme
        AppTheme.LIGHT    -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
