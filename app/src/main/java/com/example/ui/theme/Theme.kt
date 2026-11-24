package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val IosLightColorScheme = lightColorScheme(
    primary = IosBlue,
    onPrimary = Color.White,
    primaryContainer = IosBlueSoft,
    onPrimaryContainer = IosBlue,
    secondary = IosGreen,
    onSecondary = Color.White,
    secondaryContainer = IosGreenSoft,
    onSecondaryContainer = Color(0xFF1A7F37),
    tertiary = IosOrange,
    onTertiary = Color.White,
    tertiaryContainer = IosOrangeSoft,
    onTertiaryContainer = Color(0xFFB25E00),
    error = IosRed,
    onError = Color.White,
    errorContainer = IosRedSoft,
    onErrorContainer = IosRed,
    background = IosBackground,
    onBackground = IosLabelPrimary,
    surface = IosCardSurface,
    onSurface = IosLabelPrimary,
    surfaceVariant = IosSecondarySurface,
    onSurfaceVariant = IosLabelSecondary,
    outline = IosSeparator,
    outlineVariant = IosHairline
)

private val IosShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(22.dp)
)

@Composable
fun EpubCleanAITheme(
    content: @Composable () -> Unit
) {
    // Consistently render the requested iOS minimalist light aesthetic (#F2F2F7 canvas, #FFFFFF cards, #007AFF accent)
    MaterialTheme(
        colorScheme = IosLightColorScheme,
        typography = AppTypography,
        shapes = IosShapes,
        content = content
    )
}
