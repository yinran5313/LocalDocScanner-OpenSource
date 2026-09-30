package com.localdoc.scanner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF166534),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCFCE7),
    onPrimaryContainer = Color(0xFF052E16),
    secondary = Color(0xFF0F766E),
    background = Color(0xFFF7F9F8),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE8EFEB)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF86EFAC),
    onPrimary = Color(0xFF052E16),
    primaryContainer = Color(0xFF14532D),
    secondary = Color(0xFF5EEAD4)
)

@Composable
fun LocalDocScannerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
