package app.batstats.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class ThemeContrastTest {
    private fun ratio(a: Color, b: Color): Double = (max(a.luminance(), b.luminance()) + .05) / (min(a.luminance(), b.luminance()) + .05)
    @Test fun normalTextAndFixedRolesMeetContrastInBothPalettes() {
        listOf(AurDarkTheme, AurLightTheme).forEach { s ->
            val pairs = listOf(s.primary to s.onPrimary, s.secondary to s.onSecondary, s.tertiary to s.onTertiary,
                s.primaryContainer to s.onPrimaryContainer, s.secondaryContainer to s.onSecondaryContainer,
                s.tertiaryContainer to s.onTertiaryContainer, s.surface to s.onSurface,
                s.surfaceVariant to s.onSurfaceVariant, s.error to s.onError, s.errorContainer to s.onErrorContainer)
            val fixed = listOf(Triple(s.primaryFixed, s.primaryFixedDim, listOf(s.onPrimaryFixed, s.onPrimaryFixedVariant)),
                Triple(s.secondaryFixed, s.secondaryFixedDim, listOf(s.onSecondaryFixed, s.onSecondaryFixedVariant)),
                Triple(s.tertiaryFixed, s.tertiaryFixedDim, listOf(s.onTertiaryFixed, s.onTertiaryFixedVariant)))
            (pairs + fixed.flatMap { (light, dim, text) -> text.flatMap { listOf(light to it, dim to it) } }).forEach { (background, foreground) ->
                assertTrue("Text contrast ${ratio(background, foreground)} for $background / $foreground", ratio(background, foreground) >= 4.5)
            }
            assertTrue("Control outline contrast", ratio(s.outline, s.surface) >= 3.0)
        }
    }
}
