package app.batstats.ui.screens

import androidx.compose.runtime.Composable
import app.batstats.battery.apps.AppUsageBasis
import app.batstats.battery.data.sampling.ChargerType
import app.batstats.battery.measurement.CapacityConfidence
import app.batstats.battery.measurement.CurrentCalibration
import app.batstats.battery.measurement.CurrentSign
import app.batstats.battery.measurement.CurrentUnit
import app.batstats.battery.measurement.EtaBasis
import app.batstats.battery.measurement.PowerState
import app.batstats.ui.FIXED_TIME_MS
import app.batstats.ui.PhonePreview
import app.batstats.ui.ScreenPreviews
import app.batstats.ui.ScreenshotTheme
import app.batstats.ui.TallPhonePreview
import app.batstats.ui.components.chart.TimePoint
import app.batstats.ui.components.chart.TimeWindow
import app.batstats.ui.screens.now.DrainState
import app.batstats.ui.screens.now.Eta
import app.batstats.ui.screens.now.HealthState
import app.batstats.ui.screens.now.HeroState
import app.batstats.ui.screens.now.NowContent
import app.batstats.ui.screens.now.NowUiState
import app.batstats.ui.screens.now.Readouts
import app.batstats.ui.screens.now.SinceUnplugState
import app.batstats.ui.screens.now.TodayState
import app.batstats.ui.screens.now.TopApp
import app.batstats.ui.screens.now.TopAppsState
import app.batstats.ui.screens.now.TraceRange
import app.batstats.ui.screens.now.TraceState
import com.android.tools.screenshot.PreviewTest
import kotlin.math.cos
import kotlin.math.sin

// Times render in the suite's pinned UTC/en-US; every trace ends at FIXED_TIME_MS (09:20).
private const val SECOND = 1_000L
private const val MINUTE = 60 * SECOND
private const val HOUR = 60 * MINUTE

/** The live window at 2 s: screen-on drain with bursts (a scroll, a video start). */
private val liveDrain = List(301) { i ->
    val t = FIXED_TIME_MS - TraceRange.LIVE.spanMs + i * 2 * SECOND
    val burst = if (i in 120..150) -520.0 * sin((i - 120) / 30.0 * Math.PI) else 0.0
    TimePoint(t, -372.0 - 64 * (0.5 + 0.5 * sin(i / 7.0)) - 22 * cos(i / 2.3) + burst)
}

/** The last hour at 30 s: on battery with the screen off, then plugged in at 08:55 and charging with a taper. */
private val hourPlugIn = List(121) { i ->
    val t = FIXED_TIME_MS - HOUR + i * 30 * SECOND
    val value = when {
        i < 70 -> -96.0 - 30 * (0.5 + 0.5 * sin(i / 3.0))
        i < 72 -> 380.0
        else -> 1_620.0 - (i - 72) * 3.4 + 45 * sin(i / 4.0)
    }
    TimePoint(t, value)
}

private val topApps = TopAppsState.Ready(
    rows = listOf(
        TopApp(10_123, "com.android.chrome", "Chrome", 124.0, 0.31f),
        TopApp(10_201, "com.google.android.youtube", "YouTube", 58.2, 0.15f),
        TopApp(10_311, "com.google.android.apps.maps", "Maps", 7.4, 0.02f),
    ),
    basis = AppUsageBasis.DELTA,
    capturedAtMs = FIXED_TIME_MS - 11 * MINUTE,
)

private val sinceUnplug = SinceUnplugState(
    startedAtMs = FIXED_TIME_MS - 3 * HOUR - 10 * MINUTE,
    throughMs = FIXED_TIME_MS,
    screenOn = DrainState(durationMs = 62 * MINUTE, currentMa = 412.0, percentPerHour = 9.1),
    screenOff = DrainState(durationMs = 128 * MINUTE, currentMa = 58.0, percentPerHour = 1.3),
    deepSleepPercent = 86.0,
    paused = false,
)

private fun discharging() = NowUiState(
    hero = HeroState(
        hasReading = true,
        level = 67,
        power = PowerState.DISCHARGING,
        eta = Eta(5 * HOUR + 40 * MINUTE, EtaBasis.LIVE_RATE),
        monitoring = true,
    ),
    readouts = Readouts(currentMa = -412.0, powerW = -1.59, temperatureC = 31.5, voltageV = 3.87),
    trace = TraceState(
        range = TraceRange.LIVE,
        points = liveDrain,
        window = TimeWindow(FIXED_TIME_MS - TraceRange.LIVE.spanMs, FIXED_TIME_MS),
        maxGapMs = 95_000L,
    ),
    sinceUnplug = sinceUnplug,
    today = TodayState(usedMah = 1_240.0, chargedMah = 800.0, screenOnMs = 2 * HOUR + 10 * MINUTE),
    health = HealthState(capacityMah = 4_210, confidence = CapacityConfidence.MEDIUM, healthPercent = 94.0),
    topApps = topApps,
)

private fun charging() = discharging().copy(
    hero = HeroState(
        hasReading = true,
        level = 54,
        power = PowerState.CHARGING,
        charger = ChargerType.AC,
        eta = Eta(HOUR + 12 * MINUTE, EtaBasis.ANDROID),
        monitoring = true,
    ),
    readouts = Readouts(currentMa = 1_452.0, powerW = 6.21, temperatureC = 34.2, voltageV = 4.28),
    trace = TraceState(
        range = TraceRange.HOUR,
        points = hourPlugIn,
        window = TimeWindow(FIXED_TIME_MS - HOUR, FIXED_TIME_MS),
    ),
    topApps = topApps.copy(basis = AppUsageBasis.ABSOLUTE),
)

/** Monitoring stopped: no time left, the drain numbers frozen at the stop, the live trace from demand polls only. */
private fun monitoringOff() = discharging().copy(
    hero = HeroState(hasReading = true, level = 67, power = PowerState.DISCHARGING, monitoring = false),
    trace = discharging().trace.copy(points = liveDrain.takeLast(90)),
    sinceUnplug = sinceUnplug.copy(throughMs = FIXED_TIME_MS - 40 * MINUTE, paused = true),
)

/** First launch: a reading, nothing recorded yet, no per-app data. */
private fun empty() = NowUiState(
    hero = HeroState(hasReading = true, level = 80, power = PowerState.DISCHARGING, monitoring = false),
    readouts = Readouts(currentMa = -388.0, powerW = -1.54, temperatureC = 29.8, voltageV = 3.97),
    trace = TraceState(window = TimeWindow(FIXED_TIME_MS - TraceRange.LIVE.spanMs, FIXED_TIME_MS), maxGapMs = 95_000L),
)

@Composable
private fun NowPreviewContent(state: NowUiState) {
    NowContent(state = state, onEvent = {})
}

@PreviewTest
@ScreenPreviews
@Composable
fun NowScreenPreview() {
    ScreenshotTheme { NowPreviewContent(discharging()) }
}

/** Charging on AC: time to full from Android, the 1 h trace across the plug-in (amber, then chartreuse). */
@PreviewTest
@TallPhonePreview
@Composable
fun NowScreenChargingPreview() {
    ScreenshotTheme { NowPreviewContent(charging()) }
}

@PreviewTest
@TallPhonePreview
@Composable
fun NowScreenMonitoringOffPreview() {
    ScreenshotTheme { NowPreviewContent(monitoringOff()) }
}

@PreviewTest
@TallPhonePreview
@Composable
fun NowScreenEmptyPreview() {
    ScreenshotTheme { NowPreviewContent(empty()) }
}

/** A detected correction was applied (mA reporting): the notice with Undo / Keep above the hero. */
@PreviewTest
@PhonePreview
@Composable
fun NowScreenCalibrationPreview() {
    ScreenshotTheme {
        NowPreviewContent(discharging().copy(calibrationNotice = CurrentCalibration(CurrentUnit.MILLIAMPS, CurrentSign.NORMAL)))
    }
}
