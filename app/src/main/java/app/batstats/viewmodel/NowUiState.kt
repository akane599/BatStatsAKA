package app.batstats.viewmodel

import androidx.compose.runtime.Immutable
import app.batstats.battery.apps.AppLabel
import app.batstats.battery.apps.AppUsageBasis
import app.batstats.battery.data.sampling.ChargerType
import app.batstats.battery.measurement.CapacityConfidence
import app.batstats.battery.measurement.CurrentCalibration
import app.batstats.battery.measurement.EtaBasis
import app.batstats.battery.measurement.PowerState
import app.batstats.ui.components.chart.TimePoint
import app.batstats.ui.components.chart.TimeWindow

/**
 * Everything the Now screen renders, as plain values: numbers stay numbers (formatting and units happen in the UI,
 * with the viewer's locale), stored enum names are already parsed, and a `null` part means "nothing to show yet".
 */
@Immutable
data class NowUiState(
    /** The latest reading's time (the clock before one): "today" for dates in titles and captions. */
    val nowMs: Long = 0,
    val hero: HeroState = HeroState(),
    val readouts: Readouts = Readouts(),
    val trace: TraceState = TraceState(),
    /** The newest on-battery window; null when no DISCHARGE session was ever recorded. */
    val sinceUnplug: SinceUnplugState? = null,
    /** Null when today's summary row doesn't exist yet. */
    val today: TodayState? = null,
    /** Null until some session produced a capacity estimate. */
    val health: HealthState? = null,
    val topApps: TopAppsState = TopAppsState.Empty,
    /** The applied correction while its notice is pending (Undo / Keep), else null. */
    val calibrationNotice: CurrentCalibration? = null,
    val useFahrenheit: Boolean = false,
)

@Immutable
data class HeroState(
    /** False until Android delivered a first reading. */
    val hasReading: Boolean = false,
    val level: Int? = null,
    val power: PowerState = PowerState.UNKNOWN,
    val charger: ChargerType? = null,
    /** Time left (discharging; only while monitoring) or to full (charging; Android's, also without monitoring). */
    val eta: Eta? = null,
    val monitoring: Boolean = false,
    /** Android refused the foreground-service start; cleared by the next attempt or once monitoring runs. */
    val startBlocked: Boolean = false,
)

/** [basis] is null when the stored name is unknown (e.g. written by a newer build). */
@Immutable
data class Eta(val remainingMs: Long, val basis: EtaBasis?)

/** Live values, calibrated; current and power are signed: positive = into the battery. */
@Immutable
data class Readouts(
    val currentMa: Double? = null,
    val powerW: Double? = null,
    val temperatureC: Double? = null,
    val voltageV: Double? = null,
)

enum class TraceRange(val spanMs: Long) {
    LIVE(10 * 60_000L),
    HOUR(60 * 60_000L),
    SIX_HOURS(6 * 60 * 60_000L),
    DAY(24 * 60 * 60_000L),
}

/**
 * The current trace in mA (calibrated with today's calibration; stored rows keep raw values). [points] carry gap
 * markers (`value = null`) where monitoring restarted or collection was interrupted; [maxGapMs] also breaks the
 * line across missing readings. Chart types, because the ViewModel downsamples them with ChartMath.
 */
@Immutable
data class TraceState(
    val range: TraceRange = TraceRange.LIVE,
    val points: List<TimePoint> = emptyList(),
    val window: TimeWindow? = null,
    val maxGapMs: Long? = null,
)

/**
 * The on-battery window "Since unplug" shows: the open DISCHARGE session ([current]: since the last unplug or Reset,
 * [endedAtMs] = its latest save), or, while plugged in or not monitoring, the newest closed one ("Last on battery").
 * Every figure comes from that one session row.
 */
@Immutable
data class SinceUnplugState(
    val current: Boolean,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val screenOn: DrainState,
    val screenOff: DrainState,
    /** CPU deep sleep as a percent of the session's observed time; null before any interval was observed. */
    val deepSleepPercent: Double?,
)

/** One screen state's discharge: its duration, average drain in mA (positive) and in % of capacity per hour. */
@Immutable
data class DrainState(
    val durationMs: Long = 0,
    val currentMa: Double? = null,
    val percentPerHour: Double? = null,
)

@Immutable
data class TodayState(
    val usedMah: Double,
    val chargedMah: Double,
    val screenOnMs: Long,
)

@Immutable
data class HealthState(
    val capacityMah: Int,
    val confidence: CapacityConfidence,
    /** Capacity vs the design capacity (Settings, else the battery's sysfs value); null when neither is known. */
    val healthPercent: Double?,
)

@Immutable
sealed interface TopAppsState {
    /** No cached per-app data (Now never runs a privileged dump), or nothing used power. */
    data object Empty : TopAppsState

    @Immutable
    data class Ready(
        val rows: List<TopApp>,
        val basis: AppUsageBasis,
        val capturedAtMs: Long,
    ) : TopAppsState
}

/** [label] is never a raw id ([AppLabel]); [share] is this app's part of all apps' power over the same window. */
@Immutable
data class TopApp(
    val uid: Int,
    val packageName: String,
    val label: AppLabel,
    val powerMah: Double,
    val share: Float,
)

/** What the Now screen asks for; the ViewModel handles state changes, NowScreen handles navigation. */
sealed interface NowEvent {
    data object ToggleMonitoring : NowEvent
    data class SelectRange(val range: TraceRange) : NowEvent
    data object ResetObservation : NowEvent
    data object UndoCalibration : NowEvent
    data object KeepCalibration : NowEvent
    data object OpenHistory : NowEvent
    data object OpenHealth : NowEvent
    data object OpenApps : NowEvent
    data class OpenApp(val uid: Int, val packageName: String) : NowEvent
}
