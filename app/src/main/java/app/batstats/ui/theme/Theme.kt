package app.batstats.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

private val LocalBatColors = staticCompositionLocalOf { BatColors.Default }
private val LocalChartColors = staticCompositionLocalOf { chartColors(BatDarkColorScheme) }
private val LocalSpacing = staticCompositionLocalOf { Spacing() }

/** Semantic charge/drain/heat/info colors ([BatColors]). */
val MaterialTheme.batColors: BatColors
    @Composable @ReadOnlyComposable get() = LocalBatColors.current

/** Chart line, fill, grid and axis colors ([ChartColors]); follows the OLED surfaces. */
val MaterialTheme.chartColors: ChartColors
    @Composable @ReadOnlyComposable get() = LocalChartColors.current

/** Spacing scale 4/8/12/16/24/32/48 dp ([Spacing]). */
val MaterialTheme.spacing: Spacing
    @Composable @ReadOnlyComposable get() = LocalSpacing.current

/**
 * BatStats theme: always dark. [oled] swaps every surface for pure black ([oledSurfaces]); [dynamicColor]
 * (Android 12+, opt-in) takes only the primary and secondary families from the wallpaper. Semantic and chart colors
 * never change. Also provides `MaterialTheme.batColors`, `.chartColors`, `.spacing`, [BatTypography]
 * (numbers: `MaterialTheme.typography.numeric*`) and [BatShapes]; durations/easings live in [BatMotion].
 */
@Composable
fun MainTheme(
    oled: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val accents = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        withDynamicAccents(BatDarkColorScheme, dynamicDarkColorScheme(LocalContext.current))
    } else {
        BatDarkColorScheme
    }
    val colorScheme = if (oled) oledSurfaces(accents) else accents
    CompositionLocalProvider(
        LocalBatColors provides BatColors.Default,
        LocalChartColors provides chartColors(colorScheme),
        LocalSpacing provides Spacing(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = BatTypography,
            shapes = BatShapes,
            content = content,
        )
    }
}
