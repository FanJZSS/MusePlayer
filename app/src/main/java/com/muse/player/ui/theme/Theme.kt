package com.muse.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val MuseBlue = Color(0xFF4DA3FF)

private val DarkColors = darkColorScheme(
    primary = MuseBlue,
    onPrimary = Color(0xFF062444),
    primaryContainer = Color(0xFF13395C),
    onPrimaryContainer = Color(0xFFD6E9FF),
    secondary = Color(0xFF8FCBFF),
    onSecondary = Color(0xFF062444),
    secondaryContainer = Color(0xFF13395C),
    onSecondaryContainer = Color(0xFFD6E9FF),
    tertiary = Color(0xFF8FCBFF),
    tertiaryContainer = Color(0xFF13395C),
    onTertiaryContainer = Color(0xFFD6E9FF),
    background = Color.Black,
    onBackground = Color(0xFFE6EEF9),
    surface = Color.Black,
    onSurface = Color(0xFFE6EEF9),
    surfaceVariant = Color(0xFF1A2537),
    onSurfaceVariant = Color(0xFF9AA9BF),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = Color(0xFF161616),
    surfaceContainerHighest = Color(0xFF1C1C1C),
    outline = Color(0xFF2A3850),
    error = Color(0xFFFF6B6B)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1D6FD1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E9FF),
    onPrimaryContainer = Color(0xFF06213D),
    secondary = Color(0xFF3C6E9E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E9FF),
    onSecondaryContainer = Color(0xFF06213D),
    tertiary = Color(0xFF3C6E9E),
    tertiaryContainer = Color(0xFFD6E9FF),
    onTertiaryContainer = Color(0xFF06213D),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF10151E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF10151E),
    surfaceVariant = Color(0xFFE6ECF5),
    onSurfaceVariant = Color(0xFF4A5668),
    outline = Color(0xFFC3CDDC)
)

@Composable
fun MuseTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
