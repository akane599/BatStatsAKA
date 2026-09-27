package app.batstats.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.batstats.R
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.diagnostics.DiagnosticCode
import app.batstats.battery.diagnostics.DiagnosticEvent
import app.batstats.battery.diagnostics.DiagnosticReport
import app.batstats.battery.measurement.Observation
import app.batstats.battery.measurement.ObservationSummary
import app.batstats.battery.measurement.ObservedBucket
import app.batstats.battery.measurement.PowerState
import app.batstats.battery.util.DetailedStatsCollector
import app.batstats.battery.util.ShellRunner
import app.batstats.ui.FIXED_TIME_MS
import app.batstats.ui.PhonePreview
import app.batstats.ui.TallPhonePreview
import app.batstats.ui.ScreenPreviews
import app.batstats.ui.ScreenshotTheme
import com.android.tools.screenshot.PreviewTest
import java.time.Instant

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS

private fun sampleReading(): BatterySample = BatterySample(
    timestamp = FIXED_TIME_MS - 30_000L,
    levelPercent = 64,
    status = 3,
    plugged = 0,
    currentNowUa = -412_000L,
    chargeCounterUah = 2_950_000L,
    voltageMv = 3_912,
    temperatureDeciC = 312,
    health = 2,
    screenOn = true,
    currentAverageUa = -398_000L,
    energyNwh = 11_540_000_000L,
    cycleCount = 187,
    etaMs = 5 * HOUR_MS + 40 * MINUTE_MS,
    etaBasis = "observed discharge rate",
    source = "BatteryManager",
)

private fun sampleObservation(): ObservationSummary = ObservationSummary(
    startedAt = FIXED_TIME_MS - 3 * HOUR_MS,
    latest = Observation(
        wallMs = FIXED_TIME_MS - 30_000L,
        elapsedMs = 86_400_000L,
        uptimeMs = 61_200_000L,
        level = 64,
        chargeUah = 2_950_000L,
        currentUa = -412_000L,
        voltageMv = 3_912,
        power = PowerState.DISCHARGING,
        interactive = true,
        dozing = false,
        generation = "obs-1",
    ),
    screenOn = ObservedBucket(
        durationMs = 70 * MINUTE_MS,
        chargeCoveredMs = 70 * MINUTE_MS,
        chargeChangeUah = 520_000L,
        energyCoveredMs = 70 * MINUTE_MS,
        energyMwh = 2_030.0,
    ),
    screenOff = ObservedBucket(
        durationMs = 105 * MINUTE_MS,
        chargeCoveredMs = 100 * MINUTE_MS,
        chargeChangeUah = 96_000L,
        energyCoveredMs = 100 * MINUTE_MS,
        energyMwh = 372.0,
    ),
    cpuSuspendMs = 72 * MINUTE_MS,
    cpuObservedMs = 105 * MINUTE_MS,
    dozeMs = 38 * MINUTE_MS,
    gaps = 1,
    counterGaps = 0,
    stopped = false,
)

private fun sampleEvents(): List<DiagnosticEvent> = listOf(
    DiagnosticEvent(DiagnosticCode.MONITORING_STARTED, FIXED_TIME_MS - 3 * HOUR_MS, FIXED_TIME_MS - 3 * HOUR_MS),
    DiagnosticEvent(DiagnosticCode.ACCESS_SHIZUKU, FIXED_TIME_MS - 3 * HOUR_MS + MINUTE_MS, FIXED_TIME_MS - 3 * HOUR_MS + MINUTE_MS),
    DiagnosticEvent(DiagnosticCode.OBSERVATION_GAP, FIXED_TIME_MS - 2 * HOUR_MS, FIXED_TIME_MS - 2 * HOUR_MS),
    DiagnosticEvent(
        DiagnosticCode.ADVANCED_READ_FAILED,
        FIXED_TIME_MS - 90 * MINUTE_MS,
        FIXED_TIME_MS - 60 * MINUTE_MS,
        count = 3,
    ),
    DiagnosticEvent(DiagnosticCode.ADVANCED_RECOVERED, FIXED_TIME_MS - 45 * MINUTE_MS, FIXED_TIME_MS - 45 * MINUTE_MS),
)

@Composable
private fun populatedState(): DiagnosticsUiState = DiagnosticsUiState(
    readingText = DiagnosticReport.reading(sampleReading()),
    observationText = DiagnosticReport.observation(sampleObservation(), monitoring = true),
    accessText = stringResource(R.string.diagnostic_access_values, ShellRunner.Mode.SHIZUKU.name, "true", "true"),
    advancedText = stringResource(
        R.string.diagnostic_advanced_values,
        Instant.ofEpochMilli(FIXED_TIME_MS - 2 * MINUTE_MS).toString(),
        Instant.ofEpochMilli(FIXED_TIME_MS - 20 * HOUR_MS).toString(),
        "4",
        (20 * HOUR_MS).toString(),
        0,
    ),
    ordinaryError = null,
    advancedError = null,
    storageFailed = false,
    events = sampleEvents(),
    shareFailed = false,
)

@Composable
private fun unavailableState(): DiagnosticsUiState = DiagnosticsUiState(
    readingText = DiagnosticReport.reading(null),
    observationText = DiagnosticReport.observation(ObservationSummary(), monitoring = false),
    accessText = stringResource(R.string.diagnostic_access_values, ShellRunner.Mode.NONE.name, "false", "false"),
    advancedText = stringResource(R.string.diagnostic_no_advanced),
    ordinaryError = "State events unavailable; observation is paused, ordinary battery readings continue",
    advancedError = DetailedStatsCollector.NO_ACCESS_MESSAGE,
    storageFailed = true,
    events = emptyList(),
    shareFailed = true,
)

@PreviewTest
@ScreenPreviews
@Composable
fun DiagnosticsScreenPreview() {
    ScreenshotTheme {
        DiagnosticsContent(
            state = populatedState(),
            onBack = {},
            onRefresh = {},
            onShareReport = {},
        )
    }
}

@PreviewTest
@PhonePreview
@Composable
fun DiagnosticsScreenOledPreview() {
    ScreenshotTheme(oledBlack = true) {
        DiagnosticsContent(
            state = populatedState(),
            onBack = {},
            onRefresh = {},
            onShareReport = {},
        )
    }
}

/** Nothing recorded, no access, and the share chooser failed: error line under the buttons, unavailable readings. */
@PreviewTest
@TallPhonePreview
@Composable
fun DiagnosticsScreenUnavailablePreview() {
    ScreenshotTheme {
        DiagnosticsContent(
            state = unavailableState(),
            onBack = {},
            onRefresh = {},
            onShareReport = {},
        )
    }
}
