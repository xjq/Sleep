package com.self.sleepcollect.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Navy = Color(0xFF1B2340)
private val Indigo = Color(0xFF3D4C8A)
private val SoftGold = Color(0xFFC9A227)
private val Cloud = Color(0xFFF4F6FB)
private val Ink = Color(0xFF1A1C22)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    secondary = SoftGold,
    onSecondary = Ink,
    background = Cloud,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE6E9F4),
    onSurfaceVariant = Color(0xFF444656),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB8C2FF),
    onPrimary = Navy,
    secondary = Color(0xFFE6C35C),
    onSecondary = Navy,
    background = Color(0xFF12141C),
    onBackground = Color(0xFFE8EAF2),
    surface = Color(0xFF1C2030),
    onSurface = Color(0xFFE8EAF2),
    surfaceVariant = Color(0xFF2A3044),
    onSurfaceVariant = Color(0xFFC5C9D8),
    error = Color(0xFFF2B8B5),
)

@Composable
fun MiSleepTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
