package com.example.keymessage.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val TerminalColorScheme = darkColorScheme(
    primary = TerminalWhite,
    onPrimary = TerminalBlack,
    secondary = TerminalCyan,
    onSecondary = TerminalBlack,
    tertiary = TerminalRed,
    onTertiary = TerminalBlack,
    background = TerminalBlack,
    onBackground = TerminalWhite,
    surface = TerminalDarkGray,
    onSurface = TerminalWhite,
    surfaceVariant = TerminalDarkGray,
    onSurfaceVariant = TerminalWhite
)

@Composable
fun KeyMessageTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TerminalColorScheme,
        typography = TerminalTypography,
        content = content
    )
}
