package app.batstats.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.batstats.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** A point keeps its real timestamp and continuity identity. Missing values create visible gaps. */
data class ChartPoint(val timestamp: Long, val value: Double?, val observationId: String? = null, val gap: Boolean = false)

@Composable
fun TelemetryChart(title: String, unit: String, points: List<ChartPoint>) {
    val finite = remember(points) { points.mapNotNull { it.value?.takeIf(Double::isFinite) } }
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val min = finite.minOrNull() ?: 0.0
    val max = finite.maxOrNull() ?: 0.0
    val start = points.firstOrNull()?.timestamp ?: 0
    val end = points.lastOrNull()?.timestamp ?: start
    val gapLimit = remember(points) {
        val deltas = points.zipWithNext().map { (a, b) -> b.timestamp - a.timestamp }.filter { it > 0 }.sorted()
        (deltas.getOrNull(deltas.size / 2) ?: 30_000) * 3
    }
    fun number(value: Double) = String.format(Locale.getDefault(),
        if (kotlin.math.abs(value) in 0.0..<1.0 && value != 0.0) "%.2g" else "%.1f", value)
    val accessibleDescription = stringResource(R.string.chart_description, title, finite.size, number(min), number(max), unit)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$title ($unit)", style = MaterialTheme.typography.titleMedium)
            if (finite.isEmpty()) {
                Text(stringResource(R.string.chart_empty))
            } else {
                Text(stringResource(R.string.chart_range, "${number(min)} – ${number(max)}", unit, finite.size), style = MaterialTheme.typography.labelMedium)
                Canvas(Modifier.fillMaxWidth().height(140.dp).semantics {
                    contentDescription = accessibleDescription
                }) {
                    drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                    var previous: Pair<ChartPoint, Offset>? = null
                    points.forEach { point ->
                        val value = point.value?.takeIf(Double::isFinite)
                        if (value == null) { previous = null; return@forEach }
                        val x = if (end > start) ((point.timestamp - start).toDouble() / (end - start) * size.width).toFloat() else size.width / 2
                        val y = if (max > min) (size.height * (1 - (value - min) / (max - min))).toFloat() else size.height / 2
                        val position = Offset(x, y.coerceIn(2.dp.toPx(), size.height - 2.dp.toPx()))
                        previous?.let { (before, location) ->
                            if (!point.gap && point.observationId != null && point.observationId == before.observationId &&
                                point.timestamp - before.timestamp in 1..gapLimit) drawLine(color, location, position, 2.dp.toPx())
                        }
                        drawCircle(color, 2.dp.toPx(), position)
                        previous = point to position
                    }
                }
                val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                Text("${format.format(Date(start))} → ${format.format(Date(end))}", style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.chart_help), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
