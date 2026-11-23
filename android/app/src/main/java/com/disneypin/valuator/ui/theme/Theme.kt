package com.disneypin.valuator.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val RoyalNavy = Color(0xFF14244B)
val CastleBlue = Color(0xFF28559A)
val MagicGold = Color(0xFFD4A017)
val MagicGoldContainer = Color(0xFFFFF0C2)
val GrailPurple = Color(0xFF6A1B9A)
val RiskLowGreen = Color(0xFF1B7F4B)
val RiskModAmber = Color(0xFFC77700)
val RiskHighRed = Color(0xFFC62828)

private val LightColorScheme = lightColorScheme(
    primary = RoyalNavy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4F9),
    onPrimaryContainer = RoyalNavy,
    secondary = CastleBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3ECFA),
    onSecondaryContainer = Color(0xFF0E2A55),
    tertiary = MagicGold,
    onTertiary = Color(0xFF2B1F00),
    tertiaryContainer = MagicGoldContainer,
    onTertiaryContainer = Color(0xFF3A2900),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF1A1C22),
    surface = Color.White,
    onSurface = Color(0xFF1A1C22),
    surfaceVariant = Color(0xFFEBEEF6),
    onSurfaceVariant = Color(0xFF474E5D)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFB4C6FF),
    onPrimary = RoyalNavy,
    primaryContainer = Color(0xFF203466),
    onPrimaryContainer = Color(0xFFDCE4F9),
    secondary = Color(0xFF9EC0FF),
    onSecondary = Color(0xFF0B254B),
    tertiary = MagicGold,
    onTertiary = Color(0xFF2B1F00),
    background = Color(0xFF10131A),
    onBackground = Color(0xFFE4E7F0),
    surface = Color(0xFF171B24),
    onSurface = Color(0xFFE4E7F0),
    surfaceVariant = Color(0xFF242A37),
    onSurfaceVariant = Color(0xFFB8C0D2)
)

@Composable
fun DisneyPinValuatorTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
