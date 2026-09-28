package com.sscrobbler.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// M3 Expressive Purple Palette (matching logo variant 2)
val PrimaryPurple = Color(0xFF6750A4)
val PrimaryPurpleDark = Color(0xFFD0BCFF)
val OnPrimaryLight = Color(0xFFFFFFFF)
val OnPrimaryDark = Color(0xFF381E72)
val PrimaryContainerLight = Color(0xFFEADDFF)
val PrimaryContainerDark = Color(0xFF4F378B)
val OnPrimaryContainerLight = Color(0xFF21005D)
val OnPrimaryContainerDark = Color(0xFFEADDFF)

val SecondaryLavender = Color(0xFF625B71)
val SecondaryLavenderDark = Color(0xFFCCC2DC)
val SecondaryContainerLight = Color(0xFFE8DEF8)
val SecondaryContainerDark = Color(0xFF4A4458)
val OnSecondaryContainerLight = Color(0xFF1D192B)
val OnSecondaryContainerDark = Color(0xFFE8DEF8)

// Expressive Tertiary: Electric Rose / Mauve
val TertiaryMauve = Color(0xFF7D5260)
val TertiaryMauveDark = Color(0xFFEFB8C8)
val TertiaryContainerLight = Color(0xFFFFD8E4)
val TertiaryContainerDark = Color(0xFF633B48)

// Expressive Status Colors
val StatusListeningColor = Color(0xFF9C27B0)
val StatusListeningContainer = Color(0xFFF3E5F5)
val OnStatusListeningContainer = Color(0xFF4A148C)

val StatusPausedColor = Color(0xFF78909C)
val StatusPausedContainer = Color(0xFFECEFF1)
val OnStatusPausedContainer = Color(0xFF263238)

val StatusEligibleColor = Color(0xFFD0BCFF)
val StatusEligibleContainer = Color(0xFF381E72)
val OnStatusEligibleContainer = Color(0xFFEADDFF)

val StatusScrobbledColor = Color(0xFF00BFA5)
val StatusScrobbledContainer = Color(0xFFA7FFEB)
val OnStatusScrobbledContainer = Color(0xFF004D40)

val StatusSkippedColor = Color(0xFF8E8E93)
val StatusSkippedContainer = Color(0xFFE5E5EA)
val OnStatusSkippedContainer = Color(0xFF1C1C1E)

val StatusOfflineColor = Color(0xFFFF6D00)
val StatusOfflineContainer = Color(0xFFFFE0B2)
val OnStatusOfflineContainer = Color(0xFFE65100)

// Surfaces & backgrounds (Pure OLED Dark #141218 matching logo 2)
val LightBackground = Color(0xFFFEF7FF)
val LightSurface = Color(0xFFFEF7FF)
val LightSurfaceVariant = Color(0xFFE7E0EC)
val LightOnSurface = Color(0xFF1D1B20)
val LightOnSurfaceVariant = Color(0xFF49454F)

val DarkBackground = Color(0xFF141218)
val DarkSurface = Color(0xFF141218)
val DarkSurfaceVariant = Color(0xFF211F26)
val DarkOnSurface = Color(0xFFE6E1E5)
val DarkOnSurfaceVariant = Color(0xFFCAC4D0)

val LightColorScheme = lightColorScheme(
    primary = PrimaryPurple,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLavender,
    onSecondary = Color.White,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryMauve,
    onTertiary = Color.White,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = Color(0xFF31111D),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = Color(0xFF79747E)
)

val DarkColorScheme = darkColorScheme(
    primary = PrimaryPurpleDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryLavenderDark,
    onSecondary = Color(0xFF332D41),
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryMauveDark,
    onTertiary = Color(0xFF492532),
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = Color(0xFFFFD8E4),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = Color(0xFF938F99)
)
