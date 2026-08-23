package com.forge.audiobookforge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF0F1420)
private val SurfaceDark = Color(0xFF161C2A)
private val Accent = Color(0xFF7C9CFF)
private val Accent2 = Color(0xFFA78BFA)

private val DarkScheme = darkColorScheme(
    primary = Accent,
    secondary = Accent2,
    background = Ink,
    surface = SurfaceDark,
    surfaceVariant = Color(0xFF1E2637),
    onPrimary = Color.White,
    onBackground = Color(0xFFE8ECF5),
    onSurface = Color(0xFFE8ECF5),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF4459B0),
    secondary = Accent2,
)

@Composable
fun ForgeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        content = content,
    )
}
