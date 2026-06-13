package com.drishti.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = DrishtiGreen,
    secondary = DrishtiWhite,
    background = DrishtiBlack,
    surface = DrishtiDark,
    onPrimary = DrishtiBlack,
    onBackground = DrishtiWhite,
    onSurface = DrishtiWhite,
    error = DrishtiRed,
)

@Composable
fun DrishtiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = DrishtiTypography,
        content = content
    )
}
