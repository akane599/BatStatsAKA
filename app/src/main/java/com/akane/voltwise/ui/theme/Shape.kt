package com.akane.voltwise.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Radius encodes hierarchy: the bigger the container, the rounder. Shapes carry no outline — separate
 * containers with `surfaceContainer*` tiers, not borders.
 */
val BatShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), // badges, menus, tooltips
    small = RoundedCornerShape(10.dp), // chips, text fields, small controls
    medium = RoundedCornerShape(14.dp), // cards, stat tiles
    large = RoundedCornerShape(20.dp), // hero panels, FAB, navigation drawer
    extraLarge = RoundedCornerShape(28.dp), // bottom sheets, dialogs
)
