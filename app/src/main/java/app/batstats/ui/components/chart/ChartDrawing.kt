package app.batstats.ui.components.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.batstats.ui.theme.spacing
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private val KeyWidth = 12.dp
private val KeyHeight = 4.dp
private val SwatchSize = 10.dp
private val SwatchRadius = 3.dp

/** Maps epoch ms to x pixels across the plot. */
internal class XAxisMap(val left: Float, val width: Float, val startMs: Long, val spanMs: Long) {
    fun x(timeMs: Long): Float =
        if (spanMs <= 0L) left + width / 2 else left + ((timeMs - startMs).toDouble() / spanMs * width).toFloat()

    fun time(x: Float): Long =
        startMs + ((x - left).coerceIn(0f, max(width, 0f)) / max(width, 1f) * spanMs).toLong()
}

/** Maps values to y pixels between [top] (max) and [bottom] (min). */
internal class YAxisMap(val top: Float, val bottom: Float, val min: Double, val max: Double) {
    fun y(value: Double): Float =
        if (max > min) (bottom - (value - min) / (max - min) * (bottom - top)).toFloat() else (top + bottom) / 2

    /** Where a fill ends and a signed series switches color: zero, clamped into the axis. */
    val baseline: Float get() = y(if (max < min) min else 0.0.coerceIn(min, max))
}

/** Growable x/y pairs, filled in a draw cache and drawn without allocating. */
internal class DotBuffer {
    var xy = FloatArray(16)
        private set
    var size = 0
        private set

    fun add(x: Float, y: Float) {
        if (size * 2 + 2 > xy.size) xy = xy.copyOf(xy.size * 2)
        xy[size * 2] = x
        xy[size * 2 + 1] = y
        size++
    }
}

/**
 * Builds one series' line and fill paths, keeping at most first/min/max/last per pixel column, so a day of 2 s
 * samples costs no more to draw than the screen is wide. Single-point segments become [dots].
 */
internal class SeriesPathBuilder(
    private val line: Path,
    private val fill: Path?,
    private val baseline: Float,
    private val dots: DotBuffer,
) {
    private var segmentLength = 0
    private var segmentStartX = 0f
    private var segmentStartY = 0f
    private var lastX = 0f
    private var column = Int.MIN_VALUE
    private var columnCount = 0
    private var firstX = 0f
    private var firstY = 0f
    private var minY = 0f
    private var maxY = 0f
    private var endX = 0f
    private var endY = 0f

    fun add(x: Float, y: Float) {
        val key = floor(x).toInt()
        if (key != column) {
            flushColumn()
            column = key
        }
        if (columnCount == 0) {
            firstX = x
            firstY = y
            minY = y
            maxY = y
        } else {
            minY = min(minY, y)
            maxY = max(maxY, y)
        }
        endX = x
        endY = y
        columnCount++
    }

    fun breakLine() {
        flushColumn()
        if (segmentLength == 1) dots.add(segmentStartX, segmentStartY)
        if (segmentLength > 0) {
            fill?.lineTo(lastX, baseline)
            fill?.close()
        }
        segmentLength = 0
        column = Int.MIN_VALUE
    }

    private fun flushColumn() {
        if (columnCount == 0) return
        emit(firstX, firstY)
        if (columnCount > 1) {
            emit(firstX, minY)
            emit(firstX, maxY)
            emit(endX, endY)
        }
        columnCount = 0
    }

    private fun emit(x: Float, y: Float) {
        if (segmentLength == 0) {
            line.moveTo(x, y)
            fill?.moveTo(x, baseline)
            fill?.lineTo(x, y)
            segmentStartX = x
            segmentStartY = y
        } else {
            line.lineTo(x, y)
            fill?.lineTo(x, y)
        }
        lastX = x
        segmentLength++
    }
}

/** A series ready to draw: paths, isolated/marked dots, and the y where a signed series changes color. */
internal class SeriesDrawing(
    val style: SeriesStyle,
    val line: Path,
    val fill: Path?,
    val dots: DotBuffer,
    val splitY: Float,
) {
    fun colorAt(y: Float): Color = when (style) {
        is SeriesStyle.Solid -> style.color
        is SeriesStyle.Signed -> if (y <= splitY) style.positive else style.negative
    }
}

/**
 * Fills [points] from index [from] to [to] (inclusive) into a [SeriesDrawing]. Gap markers, and jumps longer than
 * [maxGapMs], break the line; with [allDots] every point also gets a dot.
 */
internal fun buildSeriesDrawing(
    points: List<TimePoint>,
    from: Int,
    to: Int,
    style: SeriesStyle,
    xMap: XAxisMap,
    yMap: YAxisMap,
    maxGapMs: Long?,
    allDots: Boolean,
): SeriesDrawing {
    val hasFill = when (style) {
        is SeriesStyle.Solid -> style.fill != null
        is SeriesStyle.Signed -> style.positiveFill != null || style.negativeFill != null
    }
    val line = Path()
    val fill = if (hasFill) Path() else null
    val dots = DotBuffer()
    val baseline = yMap.baseline
    val builder = SeriesPathBuilder(line, fill, baseline, if (allDots) DotBuffer() else dots)
    var previousMs = Long.MIN_VALUE
    for (index in from..to) {
        val point = points[index]
        val value = point.value
        if (value == null || !value.isFinite()) {
            builder.breakLine()
            previousMs = Long.MIN_VALUE
            continue
        }
        if (maxGapMs != null && previousMs != Long.MIN_VALUE && point.timeMs - previousMs > maxGapMs) builder.breakLine()
        val x = xMap.x(point.timeMs)
        val y = yMap.y(value)
        builder.add(x, y)
        if (allDots) dots.add(x, y)
        previousMs = point.timeMs
    }
    builder.breakLine()
    return SeriesDrawing(style, line, fill, dots, baseline)
}

/** Area washes, split at zero for a signed series. */
internal fun DrawScope.drawSeriesFill(series: SeriesDrawing) {
    val fill = series.fill ?: return
    when (val style = series.style) {
        is SeriesStyle.Solid -> style.fill?.let { drawPath(fill, it) }
        is SeriesStyle.Signed -> {
            style.positiveFill?.let { color -> clipRect(top = 0f, bottom = series.splitY) { drawPath(fill, color) } }
            style.negativeFill?.let { color -> clipRect(top = series.splitY, bottom = size.height) { drawPath(fill, color) } }
        }
    }
}

/** The line, split at zero for a signed series. */
internal fun DrawScope.drawSeriesLine(series: SeriesDrawing, stroke: Stroke) {
    when (val style = series.style) {
        is SeriesStyle.Solid -> drawPath(series.line, style.color, style = stroke)
        is SeriesStyle.Signed -> {
            clipRect(top = 0f, bottom = series.splitY) { drawPath(series.line, style.positive, style = stroke) }
            clipRect(top = series.splitY, bottom = size.height) { drawPath(series.line, style.negative, style = stroke) }
        }
    }
}

/** Isolated or marked readings, each with a see-through ring. */
internal fun DrawScope.drawSeriesDots(series: SeriesDrawing, dotRadius: Float, ring: Float) {
    val dots = series.dots
    for (i in 0 until dots.size) {
        val x = dots.xy[i * 2]
        val y = dots.xy[i * 2 + 1]
        drawRingedDot(series.colorAt(y), Offset(x, y), dotRadius, ring)
    }
}

/**
 * A dot with a [ring] of transparency around it, so it reads over lines and fills on any container. Needs the
 * canvas on an offscreen layer (`CompositingStrategy.Offscreen`) for the clear to punch through. `Clear` ignores
 * the paint color, so the erase reuses the dot's own color (a transparent paint could be dropped as a no-op).
 */
internal fun DrawScope.drawRingedDot(color: Color, center: Offset, radius: Float, ring: Float) {
    drawCircle(color, radius + ring, center, blendMode = BlendMode.Clear)
    drawCircle(color, radius, center)
}

/** Short line key for a series; a signed series shows both direction colors. */
@Composable
internal fun LegendKey(style: SeriesStyle, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(KeyWidth, KeyHeight)
            .drawBehind {
                val radius = CornerRadius(size.height / 2)
                when (style) {
                    is SeriesStyle.Solid -> drawRoundRect(style.color, cornerRadius = radius)
                    is SeriesStyle.Signed -> {
                        val half = Size(size.width / 2, size.height)
                        drawRoundRect(style.positive, size = half, cornerRadius = radius)
                        drawRoundRect(style.negative, topLeft = Offset(size.width / 2, 0f), size = half, cornerRadius = radius)
                    }
                }
            },
    )
}

/** Square swatch for a bar segment. */
@Composable
internal fun LegendSwatch(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(SwatchSize).background(color, RoundedCornerShape(SwatchRadius)))
}

/** Key + label, in the quiet axis-label color (text never wears the series color). */
@Composable
internal fun LegendItem(label: String, key: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
    ) {
        key()
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
