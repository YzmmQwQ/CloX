package com.ailife.clox.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * CloX Wear Material 3 theme.
 *
 * Wear M3 has no single `surface` role: screens paint [ColorScheme.background]
 * (pure black here, OLED power) and cards use the surfaceContainer roles. The
 * accent mirrors the brand blue used by the View themes.
 */
private val CloXWearColorScheme = ColorScheme(
    primary = Color(0xFF82ABD1),
    primaryDim = Color(0xFF5E86AC),
    primaryContainer = Color(0xFF0F2B44),
    onPrimary = Color(0xFF06131F),
    onPrimaryContainer = Color(0xFFD7E7F7),
    secondary = Color(0xFF9CC7E8),
    secondaryDim = Color(0xFF7399B8),
    secondaryContainer = Color(0xFF16324A),
    onSecondary = Color(0xFF06131F),
    onSecondaryContainer = Color(0xFFD7E7F7),
    tertiary = Color(0xFF82ABD1),
    tertiaryDim = Color(0xFF5E86AC),
    tertiaryContainer = Color(0xFF0F2B44),
    onTertiary = Color(0xFF06131F),
    onTertiaryContainer = Color(0xFFD7E7F7),
    background = Color.Black,
    onBackground = Color(0xFFE3E2E5),
    surfaceContainerLow = Color(0xFF0C0C0E),
    surfaceContainer = Color(0xFF141416),
    surfaceContainerHigh = Color(0xFF1E1E21),
    onSurface = Color(0xFFE3E2E5),
    onSurfaceVariant = Color(0xFFC3C6CC),
    outline = Color(0xFF8D9198),
    outlineVariant = Color(0xFF43474C),
    error = Color(0xFFFFB4AB),
    errorDim = Color(0xFF93000A),
    errorContainer = Color(0xFF93000A),
    onError = Color(0xFF690005),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun CloXWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CloXWearColorScheme, content = content)
}
