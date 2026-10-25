package dev.reelsguard.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B59), onPrimary = Color.White,
    primaryContainer = Color(0xFFB0F1DB), onPrimaryContainer = Color(0xFF00251C),
    secondary = Color(0xFF52645B), secondaryContainer = Color(0xFFD5E8DD),
    background = Color(0xFFF5F8F4), surface = Color(0xFFF5F8F4),
    surfaceContainer = Color(0xFFE9EFEB), surfaceContainerLow = Color(0xFFEEF4EF),
    surfaceContainerHigh = Color(0xFFE3EAE4),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8DD5BF), onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF005140), onPrimaryContainer = Color(0xFFB0F1DB),
    secondary = Color(0xFFB9CCC1), secondaryContainer = Color(0xFF3A4B43),
    background = Color(0xFF101512), surface = Color(0xFF101512),
    surfaceContainer = Color(0xFF1C211E), surfaceContainerLow = Color(0xFF181D1A),
    surfaceContainerHigh = Color(0xFF262C28),
)

@Composable
fun ReelsGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
