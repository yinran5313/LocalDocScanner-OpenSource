package com.localdoc.scanner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localdoc.scanner.R

private val LightColors = lightColorScheme(
    primary = Color(0xFF176B53), onPrimary = Color.White,
    primaryContainer = Color(0xFFE1F3EA), onPrimaryContainer = Color(0xFF123E30),
    secondary = Color(0xFF526781), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9EEF6), onSecondaryContainer = Color(0xFF243850),
    tertiary = Color(0xFF926B35), tertiaryContainer = Color(0xFFFFEFD9), onTertiaryContainer = Color(0xFF503811),
    background = Color(0xFFF5F7F8), onBackground = Color(0xFF172B24),
    surface = Color.White, onSurface = Color(0xFF172B24),
    surfaceVariant = Color(0xFFEBF0ED), onSurfaceVariant = Color(0xFF64736D),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7FAF8),
    surfaceContainer = Color(0xFFF0F4F2), surfaceContainerHigh = Color(0xFFEAF0ED),
    surfaceContainerHighest = Color(0xFFE1E9E4),
    outline = Color(0xFF81938A), outlineVariant = Color(0xFFDEE6E1),
    error = Color(0xFFB23B3B), errorContainer = Color(0xFFFFEDED), onErrorContainer = Color(0xFF712020)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8EDAB6), onPrimary = Color(0xFF073C29),
    primaryContainer = Color(0xFF204F3C), onPrimaryContainer = Color(0xFFCEEDDE),
    secondary = Color(0xFFB4C7E0), secondaryContainer = Color(0xFF29394D), onSecondaryContainer = Color(0xFFD6E4F5),
    tertiary = Color(0xFFE4C395), tertiaryContainer = Color(0xFF514028), onTertiaryContainer = Color(0xFFFFE4BE),
    background = Color(0xFF101A16), onBackground = Color(0xFFE4EEE8),
    surface = Color(0xFF19261F), onSurface = Color(0xFFE4EEE8),
    surfaceVariant = Color(0xFF27372F), onSurfaceVariant = Color(0xFFB0C1B6),
    surfaceContainerLowest = Color(0xFF0C1511), surfaceContainerLow = Color(0xFF15211A),
    surfaceContainer = Color(0xFF19261F), surfaceContainerHigh = Color(0xFF203027),
    surfaceContainerHighest = Color(0xFF2A3C31), outline = Color(0xFF8B9F91), outlineVariant = Color(0xFF35483C)
)
@OptIn(ExperimentalTextApi::class)
private val UiFont = FontFamily(
    Font(R.font.ui_sans, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.ui_sans, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.ui_sans, weight = FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600)))
)
private fun style(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = UiFont, fontWeight = weight, fontSize = size.sp, lineHeight = height.sp, letterSpacing = 0.sp)
private val AppTypography = Typography(
    displayLarge = style(40, 48, FontWeight.SemiBold), displayMedium = style(36, 44, FontWeight.SemiBold), displaySmall = style(32, 40, FontWeight.SemiBold),
    headlineLarge = style(30, 38, FontWeight.SemiBold), headlineMedium = style(26, 34, FontWeight.SemiBold), headlineSmall = style(22, 30, FontWeight.SemiBold),
    titleLarge = style(20, 28, FontWeight.SemiBold), titleMedium = style(16, 24, FontWeight.SemiBold), titleSmall = style(14, 21, FontWeight.Medium),
    bodyLarge = style(16, 25), bodyMedium = style(14, 22), bodySmall = style(12, 19),
    labelLarge = style(14, 20, FontWeight.Medium), labelMedium = style(12, 18, FontWeight.Medium), labelSmall = style(11, 16)
)

@Composable
fun LocalDocScannerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val mode by com.localdoc.scanner.data.rememberPreference("theme", "system")
    val selectedDark = when (mode) { "light" -> false; "dark" -> true; else -> darkTheme }
    MaterialTheme(colorScheme = if (selectedDark) DarkColors else LightColors, typography = AppTypography,
        shapes = Shapes(RoundedCornerShape(8.dp), RoundedCornerShape(12.dp), RoundedCornerShape(16.dp), RoundedCornerShape(24.dp), RoundedCornerShape(28.dp)), content = content)
}
