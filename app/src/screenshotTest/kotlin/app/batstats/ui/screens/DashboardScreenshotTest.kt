package app.batstats.ui.screens

import androidx.compose.runtime.Composable
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.measurement.Observation
import app.batstats.battery.measurement.ObservationSummary
import app.batstats.battery.measurement.ObservedBucket
import app.batstats.battery.measurement.PowerState
import app.batstats.battery.util.ShellRunner
import app.batstats.ui.FIXED_TIME_MS
import app.batstats.ui.PhonePreview
import app.batstats.ui.TallPhonePreview
import app.batstats.ui.ScreenPreviews
import app.batstats.ui.ScreenshotTheme
import com.android.tools.screenshot.PreviewTest

private const val SAMPLE_COUNT = 36
private const val SAMPLE_STEP_MS = 300_000L
private const val OBSERVATION_ID = "obs-1"

// Status 3 = BatteryManager.BATTERY_STATUS_DISCHARGING, plugged 0 = on battery, health 2 = good.
private fun latestSample(): BatterySample = BatterySample(
    id = 1_000L,
    timestamp = FIXED_TIME_MS - 15_000L,
    levelPercent = 67,
    status = 3,
    plugged = 0,
    currentNowUa = -420_000L,
    chargeCounterUah = 3_050_000L,
    voltageMv = 3_870,
    temperatureDeciC = 315,
    health = 2,
    screenOn = true,
    elapsedMs = 20_000_000L,
    uptimeMs = 12_000_000L,
    observationId = OBSERVATION_ID,
    currentAverageUa = -405_000L,
    cycleCount = 212,
    etaMs = 20_400_000L,
    etaBasis = "observed",
    source = "BatteryManager",
)

/** Three hours of 5-minute samples: screen on for the first hour, then screen off. Deterministic. */
private fun recentSamples(): List<BatterySample> = List(SAMPLE_COUNT) { index ->
    val screenOn = index < 12
    val wobble = ((index * 37) % 11 - 5) * 18_000L
    val baseUa = if (screenOn) -420_000L else -85_000L
    BatterySample(
        id = index.toLong() + 1L,
        timestamp = FIXED_TIME_MS - (SAMPLE_COUNT - index) * SAMPLE_STEP_MS,
        levelPercent = 78 - index * 11 / SAMPLE_COUNT,
        status = 3,
        plugged = 0,
        currentNowUa = baseUa + if (screenOn) wobble else wobble / 4,
        chargeCounterUah = 3_600_000L - index * 15_000L,
        voltageMv = 3_990 - index * 3,
        temperatureDeciC = 300 + (index % 7),
        health = 2,
        screenOn = screenOn,
        observationId = OBSERVATION_ID,
        source = "BatteryManager",
        boundaryReason = null,
    )
}

private fun observationSummary(): ObservationSummary = ObservationSummary(
    startedAt = FIXED_TIME_MS - 3 * 3_600_000L,
    latest = Observation(
        wallMs = FIXED_TIME_MS - 15_000L,
        elapsedMs = 20_000_000L,
        uptimeMs = 12_000_000L,
        level = 67,
        chargeUah = 3_050_000L,
        currentUa = -420_000L,
        voltageMv = 3_870,
        power = PowerState.DISCHARGING,
        interactive = true,
        dozing = false,
        generation = "gen-1",
    ),
    screenOn = ObservedBucket(
        durationMs = 3_600_000L,
        chargeCoveredMs = 3_600_000L,
        chargeChangeUah = 420_000L,
        energyCoveredMs = 3_600_000L,
        energyMwh = 1_625.4,
    ),
    screenOff = ObservedBucket(
        durationMs = 7_200_000L,
        chargeCoveredMs = 7_200_000L,
        chargeChangeUah = 170_000L,
        energyCoveredMs = 7_200_000L,
        energyMwh = 655.0,
    ),
    cpuSuspendMs = 5_400_000L,
    cpuObservedMs = 10_800_000L,
    dozeMs = 3_000_000L,
    stopped = false,
)

private fun populatedState(): DashboardUiState = DashboardUiState(
    reading = BatteryRepository.Realtime(latestSample()),
    observing = true,
    summary = observationSummary(),
    error = null,
    samples = recentSamples(),
    shizukuRunning = true,
    shizukuGranted = true,
    access = ShellRunner.Mode.SHIZUKU,
)

@Composable
private fun DashboardPreviewContent(state: DashboardUiState) {
    DashboardContent(
        state = state,
        onToggleMonitoring = {},
        onRefresh = {},
        onRequestShizukuPermission = {},
        onOpenHistory = {},
        onOpenAlarms = {},
        onOpenSettings = {},
        onOpenData = {},
        onOpenDetailedStats = {},
        onOpenDrainStats = {},
        onOpenDiagnostics = {},
    )
}

@PreviewTest
@ScreenPreviews
@Composable
fun DashboardScreenPreview() {
    ScreenshotTheme {
        DashboardPreviewContent(populatedState())
    }
}

@PreviewTest
@PhonePreview
@Composable
fun DashboardScreenOledPreview() {
    ScreenshotTheme(oledBlack = true) {
        DashboardPreviewContent(populatedState())
    }
}

/** Monitoring stopped before Android delivered any reading: placeholders, empty chart, start button. */
@PreviewTest
@PhonePreview
@Composable
fun DashboardScreenStoppedPreview() {
    ScreenshotTheme {
        DashboardPreviewContent(DashboardUiState())
    }
}

/** Collection failure while Shizuku is running but not yet authorized (authorize button + error text). */
@PreviewTest
@TallPhonePreview
@Composable
fun DashboardScreenErrorPreview() {
    ScreenshotTheme {
        DashboardPreviewContent(
            DashboardUiState(
                observing = true,
                error = "Android has not supplied a battery reading",
                shizukuRunning = true,
                shizukuGranted = false,
            ),
        )
    }
}
