package com.akane.voltwise.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.akane.voltwise.R

private fun spaceGrotesk(weight: Int) = Font(
    R.font.space_grotesk,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Space Grotesk (bundled OFL variable font, wght 300–700): display, headline and title text, and every number. */
val SpaceGrotesk = FontFamily(spaceGrotesk(400), spaceGrotesk(500), spaceGrotesk(600), spaceGrotesk(700))

/** Any style as a number style: Space Grotesk with tabular figures, so live values don't jitter as digits change. */
fun TextStyle.numeric(): TextStyle = copy(fontFamily = SpaceGrotesk, fontFeatureSettings = "tnum")

private val Base = Typography()

private fun TextStyle.grotesk(weight: FontWeight, letterSpacing: Float) =
    copy(fontFamily = SpaceGrotesk, fontWeight = weight, letterSpacing = letterSpacing.sp)

/**
 * Full M3 scale on M3 sizes. Display/headline/title use Space Grotesk; body and label keep the system sans
 * (body copy is bodyLarge, 16 sp / 24 sp line height).
 */
val BatTypography = Typography(
    displayLarge = Base.displayLarge.grotesk(FontWeight.Normal, -0.5f),
    displayMedium = Base.displayMedium.grotesk(FontWeight.Normal, -0.25f),
    displaySmall = Base.displaySmall.grotesk(FontWeight.Normal, -0.25f),
    headlineLarge = Base.headlineLarge.grotesk(FontWeight.Medium, -0.25f),
    headlineMedium = Base.headlineMedium.grotesk(FontWeight.Medium, 0f),
    headlineSmall = Base.headlineSmall.grotesk(FontWeight.Medium, 0f),
    titleLarge = Base.titleLarge.grotesk(FontWeight.Medium, 0f),
    titleMedium = Base.titleMedium.grotesk(FontWeight.Medium, 0.1f),
    titleSmall = Base.titleSmall.grotesk(FontWeight.Medium, 0.1f),
    bodyLarge = Base.bodyLarge,
    bodyMedium = Base.bodyMedium,
    bodySmall = Base.bodySmall,
    labelLarge = Base.labelLarge,
    labelMedium = Base.labelMedium,
    labelSmall = Base.labelSmall,
)

private val NumericDisplay = Base.displayLarge.numeric().copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp)
private val NumericHeadline = Base.headlineLarge.numeric().copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.25).sp)
private val NumericTitle = Base.titleLarge.numeric().copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp)
private val NumericBody = Base.bodyLarge.numeric().copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp)
private val NumericLabel = Base.labelMedium.numeric().copy(fontWeight = FontWeight.Medium, letterSpacing = 0.25.sp)

/** Hero readout (57 sp): the live level or power on the dashboard. */
val Typography.numericDisplay: TextStyle get() = NumericDisplay

/** Stat-tile value (32 sp). */
val Typography.numericHeadline: TextStyle get() = NumericHeadline

/** Secondary readout or card value (22 sp). */
val Typography.numericTitle: TextStyle get() = NumericTitle

/** Value in a list row or table cell (16 sp). */
val Typography.numericBody: TextStyle get() = NumericBody

/** Chart axis ticks, legends and compact chips (12 sp). */
val Typography.numericLabel: TextStyle get() = NumericLabel
