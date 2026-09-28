package app.batstats.ui.components.chart

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.batstats.ui.theme.chartColors

private val SparkLineWidth = 1.5.dp
private val SparkDotRadius = 2.5.dp
private val SparkRing = 1.5.dp
private val SparkGridWidth = 1.dp
private val SparkMinWidth = 64.dp
private val SparkMinHeight = 24.dp

/**
 * A tiny single-series line with no axes, for a trend inside a panel or row. Defaults to the quiet axis color;
 * pass `ChartDefaults.directionStyle(filled = false)` for a power trend (a signed style also draws the zero line).
 * Sized by [modifier] (at least 64 × 24 dp). Decorative unless [contentDescription] is set.
 *
 * @param window the x range; `null` fits the data.
 * @param markLatest dot the newest reading.
 */
@Composable
fun Sparkline(
    points: List<TimePoint>,
    modifier: Modifier = Modifier,
    style: SeriesStyle = SeriesStyle.Solid(MaterialTheme.chartColors.axisLabel),
    window: TimeWindow? = null,
    markLatest: Boolean = true,
    maxGapMs: Long? = null,
    contentDescription: String? = null,
) {
    val grid = MaterialTheme.chartColors.grid
    val series = remember(points, style, maxGapMs) { listOf(ChartSeries("", points, style, maxGapMs = maxGapMs)) }
    val model = remember(series, window) { ChartModel.of(series, window, emptyList()) }
    val described = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    // A Box, not a Spacer: a Spacer only takes a fixed size, so a min height alone would leave it 0 tall.
    Box(
        modifier
            .defaultMinSize(SparkMinWidth, SparkMinHeight)
            .then(described)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val stats = model.stats[0] ?: return@drawWithCache onDrawBehind { }
                val axis = model.axes[0]
                val dot = SparkDotRadius.toPx()
                val ring = SparkRing.toPx()
                val inset = dot + ring
                val xMap = XAxisMap(inset, size.width - 2 * inset, model.startMs, model.spanMs)
                val yMap = YAxisMap(inset, size.height - inset, axis.min, axis.max)
                val drawing = buildSeriesDrawing(points, model.from[0], model.to[0], style, xMap, yMap, maxGapMs, false)
                val stroke = Stroke(SparkLineWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val showZero = style is SeriesStyle.Signed && axis.min < 0.0 && axis.max > 0.0
                val zeroY = yMap.y(0.0)
                val gridWidth = SparkGridWidth.toPx()
                val latest = Offset(xMap.x(stats.latestTimeMs), yMap.y(stats.latest))
                onDrawBehind {
                    if (showZero) drawLine(grid, Offset(0f, zeroY), Offset(size.width, zeroY), gridWidth)
                    drawSeriesFill(drawing)
                    drawSeriesLine(drawing, stroke, dot, ring)
                    if (markLatest) drawRingedDot(drawing.colorAt(latest.y), latest, dot, ring)
                }
            },
    )
}
