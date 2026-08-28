package com.grupomds.sga.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val SgaColors = lightColorScheme(
    primary = Color(0xFF155E93),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8EBFA),
    onPrimaryContainer = Color(0xFF0A3453),
    secondary = Color(0xFFE88900),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE5BF),
    onSecondaryContainer = Color(0xFF4A2A00),
    tertiary = Color(0xFF2D7D5F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD6F1E4),
    onTertiaryContainer = Color(0xFF123E2F),
    background = Color(0xFFF4F7FA),
    onBackground = Color(0xFF17212B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17212B),
    surfaceVariant = Color(0xFFE8EEF3),
    onSurfaceVariant = Color(0xFF4E5C68),
    outline = Color(0xFF81909D),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val SgaTypography = Typography(
    headlineMedium = TextStyle(fontSize = 27.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun SgaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SgaColors,
        typography = SgaTypography,
        content = content
    )
}
