package com.akane.voltwise.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing scale, read through `MaterialTheme.spacing`. All padding and gaps in screens use these steps;
 * [md] (16) is the screen gutter, [sm] (12) the gap between cards, [xs] (8) the gap inside a group.
 */
@Immutable
data class Spacing(
    val xxs: Dp = 4.dp,
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val xxl: Dp = 48.dp,
)
