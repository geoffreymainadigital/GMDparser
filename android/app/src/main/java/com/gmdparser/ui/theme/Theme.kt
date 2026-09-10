package com.gmdparser.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.gmdparser.util.AppTheme

private val DarkColorScheme = darkColorScheme(
    primary = MpesaGreen,
    onPrimary = TextPrimary,
    primaryContainer = MpesaGreenDark,
    secondary = AccentCyan,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceCard,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error = AccentRed
)

private val MidnightColorScheme = darkColorScheme(
    primary = MpesaGreen,
    onPrimary = TextPrimary,
    primaryContainer = MpesaGreenDark,
    secondary = AccentCyan,
    background = MidnightBackground,
    surface = MidnightSurface,
    surfaceVariant = MidnightSurfaceCard,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error = AccentRed
)

private val ForestColorScheme = darkColorScheme(
    primary = MpesaGreen,
    onPrimary = TextPrimary,
    primaryContainer = MpesaGreenDark,
    secondary = AccentCyan,
    background = ForestBackground,
    surface = ForestSurface,
    surfaceVariant = ForestSurfaceCard,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error = AccentRed
)

private val LightColorScheme = lightColorScheme(
    primary = MpesaGreen,
    onPrimary = TextPrimaryLight,
    primaryContainer = MpesaGreenDark,
    secondary = AccentCyan,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceCard,
    onBackground = TextPrimaryLight,
    onSurface = TextPrimaryLight,
    onSurfaceVariant = TextSecondaryLight,
    error = AccentRed
)

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
