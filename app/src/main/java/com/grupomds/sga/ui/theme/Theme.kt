package com.grupomds.sga.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SgaColors = lightColorScheme(
    primary = Color(0xFF1C6FB8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8EAFE),
    onPrimaryContainer = Color(0xFF0B3657),
    secondary = Color(0xFFFF9900),
    onSecondary = Color(0xFF231A00),
    secondaryContainer = Color(0xFFFFE0B2),
    onSecondaryContainer = Color(0xFF4D2D00),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF18212A),
    surface = Color.White,
    onSurface = Color(0xFF18212A),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B)
)

@Composable
fun SgaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SgaColors,
        typography = Typography(),
        content = content
    )
}
