package com.sscrobbler.app.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Material 3 Expressive shapes: prominent, playful, highly rounded geometry
val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

val PillShape = CircleShape
val ArtworkShape = RoundedCornerShape(28.dp)
val CardShape = RoundedCornerShape(24.dp)
val ChipShape = RoundedCornerShape(12.dp)
val ButtonShape = CircleShape
