package com.vortex.explorer.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B3DF5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4DEFF),
    onPrimaryContainer = Color(0xFF1A0A6B),
    secondary = Color(0xFF5E5C71),
    tertiary = Color(0xFF00897B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9A9FF),
    onPrimary = Color(0xFF2A1386),
    primaryContainer = Color(0xFF4226C9),
    onPrimaryContainer = Color(0xFFE4DEFF),
    secondary = Color(0xFFC7C4DC),
    tertiary = Color(0xFF4DD0C0),
    background = Color(0xFF121218),
    surface = Color(0xFF121218),
)

/** Follows the system light/dark setting; uses wallpaper colours on Android 12+. */
@Composable
fun VortexTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
