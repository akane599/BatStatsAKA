package app.batstats.ui.components.chart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import app.batstats.ui.ComponentPreviews
import app.batstats.ui.FIXED_TIME_MS
import app.batstats.ui.ScreenshotTheme
import app.batstats.ui.components.Panel
import app.batstats.ui.theme.chartColors
import app.batstats.ui.theme.spacing
import com.android.tools.screenshot.PreviewTest
import kotlin.math.cos
import kotlin.math.sin

// Times render in the suite's pinned UTC/en-US; every series ends at FIXED_TIME_MS (09:20).
private const val SECOND = 1_000L
private const val MINUTE = 60 * SECOND
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

/** Last hour at 10 s: charging (taper) until 09:55 local-ish, unplugged, then screen-on bursts. */
private val liveCurrent = List(361) { i ->
    val t = FIXED_TIME_MS - HOUR + i * 10 * SECOND
    val value = when {
        i < 150 -> 1_450.0 - i * 3.2 + 60 * sin(i / 5.0)
        i < 156 -> 900.0 - (i - 150) * 220.0
        else -> -380.0 - 260 * (0.5 + 0.5 * sin(i / 9.0)).let { it * it } - 40 * cos(i / 2.3)
    }
    TimePoint(t, value)
}

/** Last 6 h at 2 min: a sampling hole (broken by maxGapMs) holding one isolated reading, then a gap marker. */
private val gappyTemperature = List(181) { i ->
    val t = FIXED_TIME_MS - 6 * HOUR + i * 2 * MINUTE
    when (i) {
        in 40..49, in 51..62 -> null
        70 -> TimePoint(t, null)
        else -> TimePoint(t, 30.5 + 4 * sin(i / 18.0) + 1.5 * sin(i / 5.0))
    }
}.filterNotNull()

private val sessionLevel = List(96) { i -> TimePoint(FIXED_TIME_MS - 95 * MINUTE + i * MINUTE, 84.0 - i * 0.24) }
private val sessionCurrent = List(96) { i ->
    TimePoint(FIXED_TIME_MS - 95 * MINUTE + i * MINUTE, if (i in 30..33) 180.0 else -436.0 + 120 * sin(i / 4.0))
}

private val capacityTrend = listOf(152, 131, 118, 97, 76, 55, 31, 12).mapIndexed { i, daysAgo ->
    TimePoint(FIXED_TIME_MS - daysAgo * DAY, 4_620.0 - i * 38 + (if (i % 3 == 0) 25.0 else -10.0))
}

@Composable
private fun ChartFrame(title: String, content: @Composable () -> Unit) {
    ScreenshotTheme {
        Panel(Modifier.padding(MaterialTheme.spacing.md), title = title) { content() }
    }
}

@PreviewTest
@ComponentPreviews
@Composable
fun LiveTracePreview() {
    ChartFrame("Power") {
        TimeSeriesChart(
            series = listOf(ChartSeries("Current", liveCurrent, ChartDefaults.directionStyle(), unit = "mA")),
            window = TimeWindow(FIXED_TIME_MS - HOUR, FIXED_TIME_MS),
            markLatest = true,
        )
    }
}

@PreviewTest
@ComponentPreviews
@Composable
fun LiveTraceScrubbedPreview() {
    CompositionLocalProvider(LocalPreviewScrubTimeMs provides FIXED_TIME_MS - 38 * MINUTE) {
        LiveTracePreview()
    }
}

@PreviewTest
@ComponentPreviews
@Composable
fun GapsChartPreview() {
    ChartFrame("Temperature") {
        TimeSeriesChart(
            series = listOf(
                ChartSeries(
                    "Temperature",
                    gappyTemperature,
                    ChartDefaults.solidStyle(MaterialTheme.chartColors.temperature, filled = true),
                    unit = "°C",
                    maxGapMs = 10 * MINUTE,
                ),
            ),
        )
    }
}

@PreviewTest
@ComponentPreviews
@Composable
fun TwoSeriesChartPreview() {
    CompositionLocalProvider(LocalPreviewScrubTimeMs provides FIXED_TIME_MS - 20 * MINUTE) {
        ChartFrame("Level and current") {
            TimeSeriesChart(
                series = listOf(
                    ChartSeries("Current", sessionCurrent, ChartDefaults.directionStyle(filled = false), unit = "mA"),
                    ChartSeries(
                        "Level",
                        sessionLevel,
                        ChartDefaults.solidStyle(MaterialTheme.colorScheme.secondary),
                        unit = "%",
                        format = NumberFormatter("%", maxDecimals = 0),
                        axisMin = 0.0,
                        axisMax = 100.0,
                    ),
                ),
            )
        }
    }
}

@PreviewTest
@ComponentPreviews
@Composable
fun CapacityTrendPreview() {
    ChartFrame("Capacity") {
        TimeSeriesChart(
            series = listOf(
                ChartSeries(
                    "Estimated capacity",
                    capacityTrend,
                    ChartDefaults.solidStyle(MaterialTheme.chartColors.voltage),
                    unit = "mAh",
                    showPoints = true,
                ),
            ),
            references = listOf(ChartReference(5_000.0, "Design 5,000 mAh")),
        )
    }
}

@PreviewTest
@ComponentPreviews
@Composable
fun EmptyChartPreview() {
    ChartFrame("Power") {
        TimeSeriesChart(
            series = listOf(ChartSeries("Current", emptyList(), ChartDefaults.directionStyle(), unit = "mA")),
            window = TimeWindow(FIXED_TIME_MS - HOUR, FIXED_TIME_MS),
        )
    }
}

private val dayLabels = listOf("Sep 26", "27", "28", "29", "30", "Oct 1", "2", "3", "4", "5", "6", "7", "8", "9")
private val drainDays = dayLabels.mapIndexed { i, label ->
    BarEntry(label, listOf(18.0 + 14 * (0.5 + 0.5 * sin(i * 1.3)), 6.0 + 5 * (0.5 + 0.5 * cos(i * 0.9))))
}

@PreviewTest
@ComponentPreviews
@Composable
fun StackedBarChartPreview() {
    val drain = MaterialTheme.chartColors.drain
    ChartFrame("Daily drain") {
        BarChart(
            entries = drainDays,
            segments = listOf(BarSegment("Screen on", drain), BarSegment("Screen off", drain.copy(alpha = 0.45f))),
            unit = "%",
            format = NumberFormatter("%", maxDecimals = 0),
            selectedIndex = drainDays.lastIndex,
            onSelect = {},
        )
    }
}

@PreviewTest
@ComponentPreviews
@Composable
fun EmptyBarChartPreview() {
    val drain = MaterialTheme.chartColors.drain
    ChartFrame("Daily drain") {
        BarChart(
            entries = emptyList(),
            segments = listOf(BarSegment("Screen on", drain), BarSegment("Screen off", drain.copy(alpha = 0.45f))),
            unit = "%",
        )
    }
}

@PreviewTest
@ComponentPreviews
@Composable
fun SparklineAndBreakdownPreview() {
    val colors = MaterialTheme.chartColors
    ScreenshotTheme {
        Column(
            Modifier.padding(MaterialTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            Panel(title = "Today") {
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
                    Column(Modifier.weight(1f)) {
                        Text("Level", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Sparkline(sessionLevel, Modifier.fillMaxWidth())
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Power", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Sparkline(liveCurrent, Modifier.fillMaxWidth(), style = ChartDefaults.directionStyle(filled = false))
                    }
                }
            }
            Panel(title = "Time on battery") {
                BreakdownBar(
                    segments = listOf(
                        BreakdownSegment("Foreground", 72.0, colors.drain),
                        BreakdownSegment("Background", 34.0, colors.voltage),
                        BreakdownSegment("Cached", 8.0, colors.axisLabel),
                    ),
                    format = { minutes -> "${minutes.toInt()} min" },
                )
            }
        }
    }
}
