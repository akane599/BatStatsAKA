package app.batstats.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.batstats.battery.apps.AppLabel
import app.batstats.battery.apps.AppUsageBasis
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.data.sampling.ChargerType
import app.batstats.battery.measurement.CapacityConfidence
import app.batstats.ui.FIXED_TIME_MS
import app.batstats.ui.PhonePreview
import app.batstats.ui.ScreenPreviews
import app.batstats.ui.ScreenshotTheme
import app.batstats.ui.TallPhonePreview
import app.batstats.ui.components.chart.TimePoint
import app.batstats.ui.components.chart.TimeWindow
import app.batstats.viewmodel.DrainState
import app.batstats.viewmodel.SessionApp
import app.batstats.viewmodel.SessionApps
import app.batstats.viewmodel.SessionCapacity
import app.batstats.viewmodel.SessionCharts
import app.batstats.viewmodel.SessionDetailsUiState
import app.batstats.viewmodel.SessionInsights
import app.batstats.viewmodel.SessionSummary
import com.android.tools.screenshot.PreviewTest
import kotlin.math.cos
import kotlin.math.sin

// Times render in the suite's pinned UTC/en-US; FIXED_TIME_MS is 09:20.
private const val SECOND = 1_000L
private const val MINUTE = 60 * SECOND
private const val HOUR = 60 * MINUTE

/** The whole scrolling page on a phone, for states whose content sits far below a 1000 dp fold. */
@Preview(name = "PhoneFull", widthDp = 400, heightDp = 2000)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
internal annotation class FullPagePreview

private class Series(val current: List<TimePoint>, val level: List<TimePoint>, val temperature: List<TimePoint>)

private fun series(start: Long, end: Long, stepMs: Long, point: (t: Long, fraction: Double) -> Triple<Double?, Double?, Double?>): Series {
    val current = ArrayList<TimePoint>()
    val level = ArrayList<TimePoint>()
    val temperature = ArrayList<TimePoint>()
    var t = start
    while (t <= end) {
        val (mA, percent, celsius) = point(t, (t - start).toDouble() / (end - start))
        current += TimePoint(t, mA)
        level += TimePoint(t, percent)
        temperature += TimePoint(t, celsius)
        t += stepMs
    }
    return Series(current, level, temperature)
}

// ---- On battery, 06:10 → 09:05 ----

private const val DISCHARGE_START = FIXED_TIME_MS - 3 * HOUR - 10 * MINUTE
private const val DISCHARGE_END = FIXED_TIME_MS - 15 * MINUTE

/** Screen on in bursts (commute, lunch-break video) at 30 s, screen off at 5 min, level 92 → 58. */
private val dischargeSeries = series(DISCHARGE_START, DISCHARGE_END, 60 * SECOND) { t, fraction ->
    val minute = (t - DISCHARGE_START) / MINUTE
    val screenOn = minute < 40 || minute in 100..128 || minute in 150..172
    val mA = if (screenOn) -410.0 - 140 * (0.5 + 0.5 * sin(minute / 3.0)) - 30 * cos(minute / 1.3) else -58.0 - 9 * sin(minute / 5.0)
    val celsius = if (screenOn) 33.0 + 1.6 * sin(minute / 9.0) else 29.5 + 0.4 * sin(minute / 7.0)
    Triple(mA, (92 - 34 * fraction).toInt().toDouble(), celsius)
}

private fun dischargeSummary() = SessionSummary(
    type = SessionType.DISCHARGE,
    recording = false,
    startedAtMs = DISCHARGE_START,
    endedAtMs = DISCHARGE_END,
    startLevel = 92,
    endLevel = 58,
    chargeMah = 1_540.0,
    energyWh = 5.9,
    averageMa = 528.0,
    // Over the 4,320 mAh the drain cells' %/h use.
    chargePercent = 35.6,
    percentPerHour = 12.2,
    counterCoverage = 1.0,
    capacity = SessionCapacity(4_320, CapacityConfidence.HIGH),
    measured = true,
)

private val dischargeDrain = SessionInsights.Drain(
    screenOn = DrainState(durationMs = 91 * MINUTE, currentMa = 812.0, percentPerHour = 18.8),
    screenOff = DrainState(durationMs = 84 * MINUTE, currentMa = 96.0, percentPerHour = 2.2),
    deepSleepPercent = 91.0,
    deepSleepScreenOff = true,
)

private val dischargeApps = SessionApps.Ready(
    rows = listOf(
        SessionApp(10_123, "com.android.chrome", AppLabel.Named("Chrome"), 312.4, 0.37f),
        SessionApp(10_201, "com.google.android.youtube", AppLabel.Named("YouTube"), 244.1, 0.29f),
        SessionApp(10_311, "com.google.android.apps.maps", AppLabel.Named("Maps"), 96.3, 0.11f),
        SessionApp(1_000, "System UID 1000", AppLabel.SystemProcess, 58.0, 0.07f),
        SessionApp(10_402, "com.whatsapp", AppLabel.Named("WhatsApp"), 41.7, 0.05f),
        SessionApp(10_517, "com.spotify.music", AppLabel.Named("Spotify"), 22.9, 0.03f),
        SessionApp(10_144, "com.google.android.gm", AppLabel.Named("Gmail"), 9.4, 0.011f),
        SessionApp(10_188, "com.android.camera", AppLabel.Named("Camera"), 6.2, 0.007f),
        SessionApp(10_623, "com.instagram.android", AppLabel.Named("Instagram"), 4.8, 0.006f),
        SessionApp(10_700, "com.removed.app", AppLabel.Unknown, 3.1, 0.004f),
        SessionApp(10_150, "com.google.android.apps.photos", AppLabel.Named("Photos"), 2.2, 0.003f),
        SessionApp(10_090, "com.google.android.deskclock", AppLabel.Named("Clock"), 1.1, 0.001f),
    ),
    othersMah = 12.5,
    othersShare = 0.015f,
    basis = AppUsageBasis.DELTA,
)

private fun discharge(apps: SessionApps? = dischargeApps) = SessionDetailsUiState.Ready(
    summary = dischargeSummary(),
    charts = SessionCharts(TimeWindow(DISCHARGE_START, DISCHARGE_END), dischargeSeries.current, dischargeSeries.level, dischargeSeries.temperature),
    insights = dischargeDrain,
    apps = apps,
    useFahrenheit = false,
    canDelete = true,
)

// ---- Charging, 07:40 → 09:15, AC, 18 → 96 % with the taper past 80 % ----

private const val CHARGE_START = FIXED_TIME_MS - HOUR - 40 * MINUTE
private const val CHARGE_END = FIXED_TIME_MS - 5 * MINUTE

private val chargeSeries = series(CHARGE_START, CHARGE_END, 60 * SECOND) { t, fraction ->
    val minute = (t - CHARGE_START) / MINUTE
    val level = if (fraction < 0.62) 18 + 62 * fraction / 0.62 else 80 + 16 * (1 - (1 - (fraction - 0.62) / 0.38) * (1 - (fraction - 0.62) / 0.38))
    val mA = if (fraction < 0.62) 2_850.0 + 60 * sin(minute / 4.0) else 2_850.0 - 2_250 * ((fraction - 0.62) / 0.38)
    val celsius = 31.0 + 7.6 * sin(fraction * Math.PI * 0.8)
    Triple(mA, level.toInt().toDouble(), celsius)
}

private fun charging() = SessionDetailsUiState.Ready(
    summary = SessionSummary(
        type = SessionType.CHARGE,
        recording = false,
        startedAtMs = CHARGE_START,
        endedAtMs = CHARGE_END,
        startLevel = 18,
        endLevel = 96,
        chargeMah = 3_410.0,
        energyWh = 13.6,
        averageMa = 2_270.0,
        chargePercent = 77.9,
        percentPerHour = 51.8,
        counterCoverage = 1.0,
        capacity = SessionCapacity(4_380, CapacityConfidence.MEDIUM),
        measured = true,
    ),
    charts = SessionCharts(TimeWindow(CHARGE_START, CHARGE_END), chargeSeries.current, chargeSeries.level, chargeSeries.temperature),
    insights = SessionInsights.Charging(
        charger = ChargerType.AC,
        averagePowerW = 9.1,
        peakPowerW = 18.4,
        peakTemperature = 38.6,
        twentyToEightyMs = 58 * MINUTE,
    ),
    apps = null,
    useFahrenheit = false,
    canDelete = true,
)

// ---- Recording since 08:38: saved rows at 30 s, then the last 10 min live at 2 s ----

private const val ACTIVE_START = FIXED_TIME_MS - 42 * MINUTE

private val activeSaved = series(ACTIVE_START, FIXED_TIME_MS - 10 * MINUTE, 30 * SECOND) { t, fraction ->
    val minute = (t - ACTIVE_START) / MINUTE.toDouble()
    Triple(-280.0 - 60 * sin(minute / 2.0), (81 - 3 * fraction).toInt().toDouble(), 31.2 + 0.5 * sin(minute / 6.0))
}
private val activeLive = series(FIXED_TIME_MS - 10 * MINUTE + 2 * SECOND, FIXED_TIME_MS, 2 * SECOND) { t, fraction ->
    val i = (t - (FIXED_TIME_MS - 10 * MINUTE)) / (2 * SECOND)
    val burst = if (i in 180L..220L) -480.0 * sin((i - 180) / 40.0 * Math.PI) else 0.0
    Triple(-330.0 - 50 * (0.5 + 0.5 * sin(i / 7.0)) - 20 * cos(i / 2.3) + burst, (78 - 2 * fraction).toInt().toDouble(), 30.9 + 0.6 * fraction)
}

private fun active() = SessionDetailsUiState.Ready(
    summary = SessionSummary(
        type = SessionType.DISCHARGE,
        recording = true,
        startedAtMs = ACTIVE_START,
        endedAtMs = FIXED_TIME_MS,
        startLevel = 81,
        endLevel = 76,
        chargeMah = 212.0,
        energyWh = 0.82,
        averageMa = 303.0,
        chargePercent = 4.9,
        percentPerHour = 7.0,
        counterCoverage = 1.0,
        capacity = null,
        measured = true,
    ),
    charts = SessionCharts(
        TimeWindow(ACTIVE_START, FIXED_TIME_MS),
        activeSaved.current + activeLive.current,
        activeSaved.level + activeLive.level,
        activeSaved.temperature + activeLive.temperature,
    ),
    insights = SessionInsights.Drain(
        screenOn = DrainState(durationMs = 42 * MINUTE, currentMa = 303.0, percentPerHour = 7.0),
        screenOff = DrainState(durationMs = 0),
        deepSleepPercent = 4.0,
        deepSleepScreenOff = false,
    ),
    apps = SessionApps.Ready(
        rows = listOf(
            SessionApp(10_123, "com.android.chrome", AppLabel.Named("Chrome"), 48.2, 0.52f),
            SessionApp(10_402, "com.whatsapp", AppLabel.Named("WhatsApp"), 17.9, 0.19f),
            SessionApp(1_000, "System UID 1000", AppLabel.SystemProcess, 9.6, 0.1f),
        ),
        othersMah = null,
        othersShare = 0f,
        basis = AppUsageBasis.DELTA,
        soFar = true,
        capturedAtMs = FIXED_TIME_MS - 6 * MINUTE,
    ),
    useFahrenheit = false,
    canDelete = false,
)

/**
 * An imported session whose readings weren't exported: header and drain from the row, no charts, no apps. No reading
 * gives the full capacity and no estimate stands in, so the header and drain fall back to mAh and mA.
 */
private fun noSamples() = discharge(apps = SessionApps.NotRecorded).copy(
    summary = dischargeSummary().copy(chargePercent = null, percentPerHour = null, capacity = null),
    charts = SessionCharts(TimeWindow(DISCHARGE_START, DISCHARGE_END)),
    insights = dischargeDrain.copy(
        screenOn = dischargeDrain.screenOn.copy(percentPerHour = null),
        screenOff = dischargeDrain.screenOff.copy(percentPerHour = null),
    ),
)

@Composable
private fun SessionDetailsPreviewContent(state: SessionDetailsUiState) {
    ScreenshotTheme { SessionDetailsContent(state = state, onEvent = {}) }
}

@PreviewTest
@ScreenPreviews
@Composable
fun SessionDetailsScreenPreview() {
    SessionDetailsPreviewContent(discharge())
}

@PreviewTest
@FullPagePreview
@Composable
fun SessionDetailsScreenDischargeFullPreview() {
    SessionDetailsPreviewContent(discharge())
}

@PreviewTest
@FullPagePreview
@Composable
fun SessionDetailsScreenNoAccessPreview() {
    SessionDetailsPreviewContent(discharge(apps = SessionApps.NoAccess))
}

@PreviewTest
@FullPagePreview
@Composable
fun SessionDetailsScreenChargingPreview() {
    SessionDetailsPreviewContent(charging())
}

@PreviewTest
@FullPagePreview
@Composable
fun SessionDetailsScreenActivePreview() {
    SessionDetailsPreviewContent(active())
}

@PreviewTest
@TallPhonePreview
@Composable
fun SessionDetailsScreenNoSamplesPreview() {
    SessionDetailsPreviewContent(noSamples())
}

@PreviewTest
@PhonePreview
@Composable
fun SessionDetailsScreenMissingPreview() {
    SessionDetailsPreviewContent(SessionDetailsUiState.Missing)
}
