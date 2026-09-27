package app.batstats.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import app.batstats.ui.ComponentPreviews
import app.batstats.ui.FIXED_TIME_MS
import app.batstats.ui.theme.MainTheme
import com.android.tools.screenshot.PreviewTest
import kotlin.math.sin

// Timestamps render in the suite's pinned UTC/en-US (app/build.gradle.kts), so these are machine-independent.
private val readings = List(30) { i ->
    ChartPoint(
        timestamp = FIXED_TIME_MS + i * 60_000L,
        value = if (i in 12..14) null else -450.0 + 120.0 * sin(i / 4.0),
        observationId = "obs-1",
    )
}

@PreviewTest
@ComponentPreviews
@Composable
fun TelemetryChartEmptyPreview() {
    MainTheme(darkTheme = isSystemInDarkTheme()) {
        Surface {
            TelemetryChart(title = "Current", unit = "mA", points = emptyList())
        }
    }
}

@PreviewTest
@ComponentPreviews
@Composable
fun TelemetryChartReadingsPreview() {
    MainTheme(darkTheme = isSystemInDarkTheme()) {
        Surface {
            TelemetryChart(title = "Current", unit = "mA", points = readings)
        }
    }
}
