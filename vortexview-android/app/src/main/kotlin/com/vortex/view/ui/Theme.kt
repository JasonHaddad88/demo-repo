package com.vortex.view.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Always dark: photos look their best on black, whatever the phone's setting. */
private val Colors = darkColorScheme(
    primary = Color(0xFFB9A9FF),
    onPrimary = Color(0xFF2A1386),
    primaryContainer = Color(0xFF4226C9),
    onPrimaryContainer = Color(0xFFE4DEFF),
    secondaryContainer = Color(0xFF34313F),
    onSecondaryContainer = Color(0xFFE6E1F5),
    background = Color.Black,
    surface = Color(0xFF121218),
    error = Color(0xFFFFB4AB),
)

@Composable
fun VortexTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
