package app.batstats.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.batstats.R
import com.android.tools.screenshot.PreviewTest

// Renders the adaptive launcher icon the way a launcher does: the 108 dp layers are scaled so their inner 72 dp
// fill the visible icon, then clipped to the launcher mask. Monochrome previews tint the themed-icon layer like
// Android 13+ does (dark glyph on a pale tile, light glyph on a dark tile).

private const val LAYER_TO_VISIBLE = 108f / 72f
private val SquircleMask = RoundedCornerShape(percent = 30)
private val RoundedSquareMask = RoundedCornerShape(percent = 18)

@Composable
private fun AdaptiveIcon(size: Dp, mask: Shape, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(mask), contentAlignment = Alignment.Center) {
        val layer = Modifier.requiredSize(size * LAYER_TO_VISIBLE)
        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = layer)
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = layer)
    }
}

@Composable
private fun ThemedIcon(size: Dp, tile: Color, glyph: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(tile), contentAlignment = Alignment.Center) {
        Image(
            painterResource(R.drawable.ic_launcher_monochrome),
            contentDescription = null,
            modifier = Modifier.requiredSize(size * LAYER_TO_VISIBLE),
            colorFilter = ColorFilter.tint(glyph),
        )
    }
}

@Composable
private fun IconRow(content: @Composable () -> Unit) {
    Row(
        Modifier.background(BatPalette.OnSurfaceVariant).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@PreviewTest
@Preview(name = "Large")
@Composable
fun LauncherIconLargePreview() = IconRow {
    AdaptiveIcon(108.dp, CircleShape)
    AdaptiveIcon(108.dp, SquircleMask)
    AdaptiveIcon(108.dp, RoundedSquareMask)
}

@PreviewTest
@Preview(name = "Small")
@Composable
fun LauncherIconSmallPreview() = IconRow {
    AdaptiveIcon(48.dp, CircleShape)
    AdaptiveIcon(48.dp, SquircleMask)
    AdaptiveIcon(48.dp, RoundedSquareMask)
}

@PreviewTest
@Preview(name = "Themed")
@Composable
fun LauncherIconMonochromePreview() = IconRow {
    ThemedIcon(108.dp, tile = BatPalette.OnChargeContainer, glyph = BatPalette.ChargeContainer)
    ThemedIcon(108.dp, tile = BatPalette.ChargeContainer, glyph = BatPalette.OnChargeContainer)
    ThemedIcon(48.dp, tile = BatPalette.OnChargeContainer, glyph = BatPalette.ChargeContainer)
    ThemedIcon(48.dp, tile = BatPalette.ChargeContainer, glyph = BatPalette.OnChargeContainer)
}

/** Full-bleed 512×512 px store icon (Play applies its own mask); copied to app/src/main/ic_launcher-playstore.png. */
@PreviewTest
@Preview(name = "PlayStore", device = "spec:width=512px,height=512px,dpi=160", showBackground = false)
@Composable
fun LauncherIconPlayStorePreview() = AdaptiveIcon(512.dp, RectangleShape)
