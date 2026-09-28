package com.sscrobbler.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Last.fm Brand Crimson & Expressive M3 accents
val PrimaryRed = Color(0xFFD51007)
val PrimaryRedDark = Color(0xFFFFB4AB)
val OnPrimaryLight = Color(0xFFFFFFFF)
val OnPrimaryDark = Color(0xFF690005)
val PrimaryContainerLight = Color(0xFFFFDAD6)
val PrimaryContainerDark = Color(0xFF93000A)
val OnPrimaryContainerLight = Color(0xFF410002)
val OnPrimaryContainerDark = Color(0xFFFFDAD6)

val SecondaryCoral = Color(0xFFA03F35)
val SecondaryCoralDark = Color(0xFFFFB4AB)
val SecondaryContainerLight = Color(0xFFFFDAD6)
val SecondaryContainerDark = Color(0xFF73332A)

// Expressive Tertiary: Electric Amber/Orange for "Eligible" threshold state
val TertiaryAmber = Color(0xFFD87700)
val TertiaryAmberDark = Color(0xFFFFB77C)
val TertiaryContainerLight = Color(0xFFFFDCC1)
val TertiaryContainerDark = Color(0xFF663800)

// Expressive Status Colors
val StatusListeningColor = Color(0xFF1E88E5)
val StatusListeningContainer = Color(0xFFD0E4FF)
val OnStatusListeningContainer = Color(0xFF001D36)

val StatusPausedColor = Color(0xFF607D8B)
val StatusPausedContainer = Color(0xFFECEFF1)
val OnStatusPausedContainer = Color(0xFF263238)

val StatusEligibleColor = Color(0xFFFF6F00)
val StatusEligibleContainer = Color(0xFFFFE0B2)
val OnStatusEligibleContainer = Color(0xFF4E2600)

val StatusScrobbledColor = Color(0xFF00897B)
val StatusScrobbledContainer = Color(0xFFC8E6C9)
val OnStatusScrobbledContainer = Color(0xFF003314)

val StatusSkippedColor = Color(0xFF757575)
val StatusSkippedContainer = Color(0xFFEEEEEE)
val OnStatusSkippedContainer = Color(0xFF212121)

val StatusOfflineColor = Color(0xFFF57C00)
val StatusOfflineContainer = Color(0xFFFFF3E0)
val OnStatusOfflineContainer = Color(0xFF4E2600)

// Standard surfaces & backgrounds
val LightBackground = Color(0xFFFBF8F8)
val LightSurface = Color(0xFFFBF8F8)
val LightSurfaceVariant = Color(0xFFF4DDDA)
val LightOnSurface = Color(0xFF201A1A)
val LightOnSurfaceVariant = Color(0xFF534341)

val DarkBackground = Color(0xFF141212)
val DarkSurface = Color(0xFF141212)
val DarkSurfaceVariant = Color(0xFF2B2424)
val DarkOnSurface = Color(0xFFEDE0DF)
val DarkOnSurfaceVariant = Color(0xFFD8C2BF)

val LightColorScheme = lightColorScheme(
    primary = PrimaryRed,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryCoral,
    onSecondary = Color.White,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = Color(0xFF3B0907),
    tertiary = TertiaryAmber,
    onTertiary = Color.White,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnStatusEligibleContainer,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = Color(0xFF857371)
)

val DarkColorScheme = darkColorScheme(
    primary = PrimaryRedDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryCoralDark,
    onSecondary = Color(0xFF5C1D17),
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = SecondaryContainerLight,
    tertiary = TertiaryAmberDark,
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = TertiaryContainerLight,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = Color(0xFFA08C8A)
)
