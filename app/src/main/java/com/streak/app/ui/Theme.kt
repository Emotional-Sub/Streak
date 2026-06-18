package com.streak.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF5A48F5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E2FF),
    secondary = Color(0xFF00838F),
    background = Color(0xFFF5F7FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFF0F3FA)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB7AFFF),
    secondary = Color(0xFF69D3DD),
    background = Color(0xFF11131A),
    surface = Color(0xFF181B23),
    primaryContainer = Color(0xFF30226D)
)

@Composable
fun StreakTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
