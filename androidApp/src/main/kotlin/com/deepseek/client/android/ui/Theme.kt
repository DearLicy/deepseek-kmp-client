package com.deepseek.client.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object DSColors {
    val Primary = Color(0xFF426EFE)
    val PrimaryAction = Color(0xFF4C6DF5)
    val PrimarySoft = Color(0xFFEEF3FD)
    val Background = Color(0xFFFFFFFF)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceMuted = Color(0xFFF8F8F8)
    val TextPrimary = Color(0xFF0F0F0F)
    val TextSecondary = Color(0xFF7D7F84)
    val Border = Color(0xFFECEDF0)
    val Danger = Color(0xFFFF3B30)
    val Success = Color(0xFF34C759)
}

private val LightColors = lightColorScheme(
    primary = DSColors.Primary,
    onPrimary = Color.White,
    primaryContainer = DSColors.PrimarySoft,
    background = DSColors.Background,
    surface = DSColors.Surface,
    onBackground = DSColors.TextPrimary,
    onSurface = DSColors.TextPrimary,
    outline = DSColors.Border,
    error = DSColors.Danger,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF507BF2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1A2744),
    background = Color(0xFF0F0F0F),
    surface = Color(0xFF171717),
    onBackground = Color(0xFFF8F8F8),
    onSurface = Color(0xFFF8F8F8),
    outline = Color(0xFF2A2B2F),
    error = Color(0xFFFF453A),
)

private val DSShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun DeepSeekTheme(preference: String = "system", content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (preference == "dark" || (preference == "system" && isSystemInDarkTheme())) DarkColors else LightColors,
        shapes = DSShapes,
        content = content,
    )
}
