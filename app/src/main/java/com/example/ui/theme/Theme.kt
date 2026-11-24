package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
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

private val IosDarkColorScheme = darkColorScheme(
    primary = IosDarkBlue,
    onPrimary = Color.White,
    primaryContainer = IosDarkBlueSoft,
    onPrimaryContainer = IosDarkBlue,
    secondary = IosDarkGreen,
    onSecondary = Color.Black,
    secondaryContainer = IosDarkGreenSoft,
    onSecondaryContainer = IosDarkGreen,
    tertiary = IosDarkOrange,
    onTertiary = Color.Black,
    tertiaryContainer = IosDarkOrangeSoft,
    onTertiaryContainer = IosDarkOrange,
    error = IosDarkRed,
    onError = Color.White,
    errorContainer = IosDarkRedSoft,
    onErrorContainer = IosDarkRed,
    background = IosDarkBackground,
    onBackground = IosDarkLabelPrimary,
    surface = IosDarkCardSurface,
    onSurface = IosDarkLabelPrimary,
    surfaceVariant = IosDarkSecondarySurface,
    onSurfaceVariant = IosDarkLabelSecondary,
    outline = IosDarkSeparator,
    outlineVariant = IosDarkHairline
)

private val IosShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun EpubCleanAITheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) IosDarkColorScheme else IosLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = IosShapes,
        content = content
    )
}
