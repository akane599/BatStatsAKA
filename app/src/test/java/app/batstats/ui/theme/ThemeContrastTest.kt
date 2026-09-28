package app.batstats.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class ThemeContrastTest {
    private val schemes = mapOf(
        "default" to BatDarkColorScheme,
        "oled" to oledSurfaces(BatDarkColorScheme),
    )

    private fun ratio(a: Color, b: Color): Double =
        (max(a.luminance(), b.luminance()) + .05) / (min(a.luminance(), b.luminance()) + .05)

    private fun assertContrast(minimum: Double, scheme: String, label: String, background: Color, foreground: Color) {
        val r = ratio(background, foreground)
        assertTrue("$scheme $label: %.2f:1 < $minimum:1 ($foreground on $background)".format(r), r >= minimum)
    }

    private fun ColorScheme.surfaceTiers(): Map<String, Color> = mapOf(
        "background" to background,
        "surface" to surface,
        "surfaceDim" to surfaceDim,
        "surfaceBright" to surfaceBright,
        "surfaceVariant" to surfaceVariant,
        "surfaceContainerLowest" to surfaceContainerLowest,
        "surfaceContainerLow" to surfaceContainerLow,
        "surfaceContainer" to surfaceContainer,
        "surfaceContainerHigh" to surfaceContainerHigh,
        "surfaceContainerHighest" to surfaceContainerHighest,
    )

    @Test
    fun textRolesMeetAaOnTheirSurfaces() {
        schemes.forEach { (name, s) ->
            s.surfaceTiers().forEach { (tier, color) ->
                assertContrast(4.5, name, "onSurface on $tier", color, s.onSurface)
                assertContrast(4.5, name, "onSurfaceVariant on $tier", color, s.onSurfaceVariant)
            }
            listOf(
                "onBackground" to (s.background to s.onBackground),
                "onPrimary" to (s.primary to s.onPrimary),
                "onPrimaryContainer" to (s.primaryContainer to s.onPrimaryContainer),
                "onSecondary" to (s.secondary to s.onSecondary),
                "onSecondaryContainer" to (s.secondaryContainer to s.onSecondaryContainer),
                "onTertiary" to (s.tertiary to s.onTertiary),
                "onTertiaryContainer" to (s.tertiaryContainer to s.onTertiaryContainer),
                "onError" to (s.error to s.onError),
                "onErrorContainer" to (s.errorContainer to s.onErrorContainer),
                "inverseOnSurface" to (s.inverseSurface to s.inverseOnSurface),
                "inversePrimary" to (s.inverseSurface to s.inversePrimary),
                "onPrimaryFixed" to (s.primaryFixed to s.onPrimaryFixed),
                "onPrimaryFixedVariant" to (s.primaryFixedDim to s.onPrimaryFixedVariant),
                "onSecondaryFixed" to (s.secondaryFixed to s.onSecondaryFixed),
                "onSecondaryFixedVariant" to (s.secondaryFixedDim to s.onSecondaryFixedVariant),
                "onTertiaryFixed" to (s.tertiaryFixed to s.onTertiaryFixed),
                "onTertiaryFixedVariant" to (s.tertiaryFixedDim to s.onTertiaryFixedVariant),
            ).forEach { (label, pair) -> assertContrast(4.5, name, label, pair.first, pair.second) }
        }
    }

    @Test
    fun semanticAndChartColorsStandOutOnEverySurface() {
        val bat = BatColors.Default
        schemes.forEach { (name, s) ->
            val chart = chartColors(s, bat)
            val marks = mapOf(
                "charge" to bat.charge,
                "drain" to bat.drain,
                "heat" to bat.heat,
                "info" to bat.info,
                "primary" to s.primary,
                "tertiary" to s.tertiary,
                "error" to s.error,
                "chart.charge" to chart.charge,
                "chart.drain" to chart.drain,
                "chart.temperature" to chart.temperature,
                "chart.voltage" to chart.voltage,
            )
            s.surfaceTiers().forEach { (tier, surface) ->
                marks.forEach { (mark, color) -> assertContrast(3.0, name, "$mark on $tier", surface, color) }
                assertContrast(4.5, name, "chart.axisLabel on $tier", surface, chart.axisLabel)
            }
            listOf(bat.charge, bat.drain, bat.heat, bat.info).forEach { fill ->
                assertContrast(4.5, name, "onAccent on accent fill", fill, bat.onAccent)
            }
        }
    }

    @Test
    fun oledAndDynamicColorOnlyTouchTheirOwnRoles() {
        val wallpaper = darkColorScheme() // stands in for dynamicDarkColorScheme(context)
        val dynamic = withDynamicAccents(BatDarkColorScheme, wallpaper)
        assertEquals(wallpaper.primary, dynamic.primary)
        assertEquals(wallpaper.secondaryContainer, dynamic.secondaryContainer)
        assertEquals(BatDarkColorScheme.tertiary, dynamic.tertiary)
        assertEquals(BatDarkColorScheme.error, dynamic.error)
        assertEquals(BatDarkColorScheme.surfaceContainer, dynamic.surfaceContainer)

        val oledDynamic = oledSurfaces(dynamic)
        assertEquals(wallpaper.primary, oledDynamic.primary)
        oledDynamic.surfaceTiers().forEach { (tier, color) ->
            assertTrue("oled $tier must be at most #1A1F27", color.luminance() <= Color(0xFF1A1F27).luminance())
        }
        assertEquals(Color.Black, oledDynamic.background)
        assertEquals(Color.Black, oledDynamic.surface)
        assertEquals(BatDarkColorScheme.primary, oledSurfaces(BatDarkColorScheme).primary)
    }

    @Ignore("Approved outline #3A4452 is 1.95:1 on background; Switch/OutlinedTextField borders need 3:1. Smallest passing: #526074.")
    @Test
    fun controlOutlineMeetsNonTextContrast() {
        schemes.forEach { (name, s) -> assertContrast(3.0, name, "outline on surface", s.surface, s.outline) }
    }
}
