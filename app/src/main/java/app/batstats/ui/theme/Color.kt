package app.batstats.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Raw palette. Screens never read it: they use `MaterialTheme.colorScheme`, [batColors] or [chartColors].
 * Values marked "derived" are not in the approved brief; they fill M3 roles the brief doesn't name.
 */
internal object BatPalette {
    // Ink-graphite surfaces, darkest to lightest.
    val Background = Color(0xFF0B0F14)
    val ContainerLow = Color(0xFF11161D)
    val Container = Color(0xFF161C24)
    val ContainerHigh = Color(0xFF1C232D)
    val ContainerHighest = Color(0xFF232B37)

    // Text and lines.
    val OnSurface = Color(0xFFE6EAF0)
    val OnSurfaceVariant = Color(0xFF9AA4B2)
    val Outline = Color(0xFF3A4452)

    // Semantic accents: energy in, energy out, heat/error, information.
    val Charge = Color(0xFFC8F25C)
    val Drain = Color(0xFFFFB35C)
    val Heat = Color(0xFFFF6B6B)
    val Info = Color(0xFF7AB8FF)

    /** Content on any accent fill (dark ink); ≥6.9:1 on all four accents. */
    val Ink = Background

    // Derived: tonal containers for the accent families and the neutral secondary family.
    val ChargeContainer = Color(0xFF2E3A12)
    val OnChargeContainer = Color(0xFFDCF79A)
    val ChargeOnLight = Color(0xFF4A5E00)
    val NeutralContainer = Color(0xFF2A3442)
    val InfoContainer = Color(0xFF1A3150)
    val OnInfoContainer = Color(0xFFCFE4FF)
    val HeatContainer = Color(0xFF5A1D1F)
    val OnHeatContainer = Color(0xFFFFDAD6)

    // "Pure black" (OLED) surfaces; the two middle containers are interpolated between the approved ends.
    val OledBackground = Color(0xFF000000)
    val OledContainerLow = Color(0xFF0A0C0F)
    val OledContainer = Color(0xFF0F1217)
    val OledContainerHigh = Color(0xFF15191F)
    val OledContainerHighest = Color(0xFF1A1F27)
}

/**
 * The default (and only) M3 scheme: dark, custom palette. Primary = brand chartreuse, secondary = neutral slate,
 * tertiary = info blue, error = heat red. Surfaces come from the ink-graphite tiers; use `surfaceContainer*`
 * for hierarchy — [ColorScheme.surfaceTint] is transparent, so `tonalElevation` adds no tint.
 */
val BatDarkColorScheme: ColorScheme = with(BatPalette) {
    darkColorScheme(
        primary = Charge,
        onPrimary = Ink,
        primaryContainer = ChargeContainer,
        onPrimaryContainer = OnChargeContainer,
        inversePrimary = ChargeOnLight,
        secondary = OnSurfaceVariant,
        onSecondary = Ink,
        secondaryContainer = NeutralContainer,
        onSecondaryContainer = OnSurface,
        tertiary = Info,
        onTertiary = Ink,
        tertiaryContainer = InfoContainer,
        onTertiaryContainer = OnInfoContainer,
        error = Heat,
        onError = Ink,
        errorContainer = HeatContainer,
        onErrorContainer = OnHeatContainer,
        background = Background,
        onBackground = OnSurface,
        surface = Background,
        onSurface = OnSurface,
        surfaceVariant = ContainerHighest,
        onSurfaceVariant = OnSurfaceVariant,
        surfaceTint = Color.Transparent,
        inverseSurface = OnSurface,
        inverseOnSurface = Container,
        outline = Outline,
        outlineVariant = Outline,
        scrim = Color.Black,
        surfaceBright = ContainerHighest,
        surfaceDim = Background,
        surfaceContainerLowest = Background,
        surfaceContainerLow = ContainerLow,
        surfaceContainer = Container,
        surfaceContainerHigh = ContainerHigh,
        surfaceContainerHighest = ContainerHighest,
        primaryFixed = Charge,
        primaryFixedDim = Charge,
        onPrimaryFixed = Ink,
        onPrimaryFixedVariant = ChargeContainer,
        secondaryFixed = OnSurfaceVariant,
        secondaryFixedDim = OnSurfaceVariant,
        onSecondaryFixed = Ink,
        onSecondaryFixedVariant = NeutralContainer,
        tertiaryFixed = Info,
        tertiaryFixedDim = Info,
        onTertiaryFixed = Ink,
        onTertiaryFixedVariant = InfoContainer,
    )
}

/** Replaces every surface/container role with the pure-black OLED tiers; accent families are untouched. */
fun oledSurfaces(scheme: ColorScheme): ColorScheme = with(BatPalette) {
    scheme.copy(
        background = OledBackground,
        surface = OledBackground,
        surfaceDim = OledBackground,
        surfaceContainerLowest = OledBackground,
        surfaceContainerLow = OledContainerLow,
        surfaceContainer = OledContainer,
        surfaceContainerHigh = OledContainerHigh,
        surfaceContainerHighest = OledContainerHighest,
        surfaceVariant = OledContainerHighest,
        surfaceBright = OledContainerHighest,
    )
}

/** Material You (opt-in): takes only the primary and secondary families from [dynamic]; surfaces stay [base]'s. */
internal fun withDynamicAccents(base: ColorScheme, dynamic: ColorScheme): ColorScheme = base.copy(
    primary = dynamic.primary,
    onPrimary = dynamic.onPrimary,
    primaryContainer = dynamic.primaryContainer,
    onPrimaryContainer = dynamic.onPrimaryContainer,
    inversePrimary = dynamic.inversePrimary,
    primaryFixed = dynamic.primaryFixed,
    primaryFixedDim = dynamic.primaryFixedDim,
    onPrimaryFixed = dynamic.onPrimaryFixed,
    onPrimaryFixedVariant = dynamic.onPrimaryFixedVariant,
    secondary = dynamic.secondary,
    onSecondary = dynamic.onSecondary,
    secondaryContainer = dynamic.secondaryContainer,
    onSecondaryContainer = dynamic.onSecondaryContainer,
    secondaryFixed = dynamic.secondaryFixed,
    secondaryFixedDim = dynamic.secondaryFixedDim,
    onSecondaryFixed = dynamic.onSecondaryFixed,
    onSecondaryFixedVariant = dynamic.onSecondaryFixedVariant,
)

/**
 * Semantic colors that keep their meaning under every theme option (Material You never changes them).
 * Read through `MaterialTheme.batColors`.
 */
@Immutable
data class BatColors(
    /** Energy flowing in: charging, brand. */
    val charge: Color,
    /** Energy flowing out: discharge, drain. */
    val drain: Color,
    /** Heat and errors (same hue as `colorScheme.error`). */
    val heat: Color,
    /** Neutral information, e.g. voltage (same hue as `colorScheme.tertiary`). */
    val info: Color,
    /** Text/icons drawn on a solid [charge], [drain], [heat] or [info] fill. */
    val onAccent: Color,
) {
    companion object {
        val Default = BatColors(
            charge = BatPalette.Charge,
            drain = BatPalette.Drain,
            heat = BatPalette.Heat,
            info = BatPalette.Info,
            onAccent = BatPalette.Ink,
        )
    }
}
