package app.batstats.ui.screens

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionChartReading
import app.batstats.battery.data.db.SessionType
import app.batstats.ui.FIXED_TIME_MS
import app.batstats.ui.PhonePreview
import app.batstats.ui.ScreenPreviews
import app.batstats.ui.ScreenshotTheme
import kotlin.math.sin

private const val SESSION_START = FIXED_TIME_MS - 2 * 60 * 60 * 1_000L
private const val SESSION_END = FIXED_TIME_MS

private fun sampleSession(): ChargeSession = ChargeSession(
    sessionId = "session-1",
    type = SessionType.DISCHARGE,
    startTime = SESSION_START,
    endTime = SESSION_END,
    startLevel = 92,
    endLevel = 41,
    deltaUah = -1_850_000L,
    avgCurrentUa = -420_000L,
    estCapacityMah = 4200,
    activeKey = null,
    observationId = "obs-1",
    lastSampleTime = SESSION_END,
    observedMs = 2 * 60 * 60 * 1_000L,
    counterCoveredMs = 2 * 60 * 60 * 1_000L,
    screenOnMs = 42 * 60 * 1_000L,
    screenOffMs = 78 * 60 * 1_000L,
    screenOnUah = -900_000L,
    screenOffUah = -950_000L,
    cpuSuspendMs = 60 * 60 * 1_000L,
    closeReason = "user_stopped",
    source = "BatteryManager",
)

/** 45 readings over the session span, with one discontinuity gap at index 22. */
private fun sampleReadings(): List<SessionChartReading> = (0 until 45).map { index ->
    val timestamp = SESSION_START + index * (2 * 60 * 60 * 1_000L / 44)
    val phase = index / 44.0
    SessionChartReading(
        timestamp = timestamp,
        currentNowUa = (-350_000 - 120_000 * sin(phase * 6.0)).toLong(),
        voltageMv = (3900 + 150 * sin(phase * 4.0 + 1.0)).toInt(),
        temperatureDeciC = (330 + 20 * sin(phase * 3.0)).toInt(),
        observationId = "obs-1",
        source = "BatteryManager",
        discontinuity = index == 22,
    )
}

@PreviewTest
@ScreenPreviews
@Composable
fun SessionDetailsScreenPreview() {
    ScreenshotTheme {
        SessionDetailsContent(
            loading = false,
            failed = false,
            session = sampleSession(),
            points = sampleReadings(),
            recordingObservation = null,
            onBack = {},
            onRefresh = {},
        )
    }
}

@PreviewTest
@PhonePreview
@Composable
fun SessionDetailsScreenOledPreview() {
    ScreenshotTheme(oledBlack = true) {
        SessionDetailsContent(
            loading = false,
            failed = false,
            session = sampleSession(),
            points = sampleReadings(),
            recordingObservation = null,
            onBack = {},
            onRefresh = {},
        )
    }
}

@PreviewTest
@PhonePreview
@Composable
fun SessionDetailsScreenLoadingPreview() {
    ScreenshotTheme {
        SessionDetailsContent(
            loading = true,
            failed = false,
            session = null,
            points = emptyList(),
            recordingObservation = null,
            onBack = {},
            onRefresh = {},
        )
    }
}

@PreviewTest
@PhonePreview
@Composable
fun SessionDetailsScreenMissingPreview() {
    ScreenshotTheme {
        SessionDetailsContent(
            loading = false,
            failed = false,
            session = null,
            points = emptyList(),
            recordingObservation = null,
            onBack = {},
            onRefresh = {},
        )
    }
}

@PreviewTest
@PhonePreview
@Composable
fun SessionDetailsScreenNoSamplesPreview() {
    ScreenshotTheme {
        SessionDetailsContent(
            loading = false,
            failed = false,
            session = sampleSession(),
            points = emptyList(),
            recordingObservation = null,
            onBack = {},
            onRefresh = {},
        )
    }
}
