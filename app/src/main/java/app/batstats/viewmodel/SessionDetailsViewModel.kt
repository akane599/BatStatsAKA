package app.batstats.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import app.batstats.battery.apps.AppInfo
import app.batstats.battery.apps.AppInfoSource
import app.batstats.battery.apps.AppLabel
import app.batstats.battery.apps.AppStatsRepository
import app.batstats.battery.apps.AppUsageBasis
import app.batstats.battery.apps.AppUsageDelta
import app.batstats.battery.apps.AppUsageRow
import app.batstats.battery.apps.AppUsageSnapshot
import app.batstats.battery.apps.AppUsageStatus
import app.batstats.battery.apps.SessionSnapshotStore
import app.batstats.battery.apps.toAppUsageSnapshot
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.CalibrationStore
import app.batstats.battery.data.HistoryMaintenance
import app.batstats.battery.data.SessionDrain
import app.batstats.battery.data.SessionEvidence
import app.batstats.battery.data.db.BatteryDatabase
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionAppUsage
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.data.db.toRow
import app.batstats.battery.data.sampling.ChargerType
import app.batstats.battery.data.sampling.DailySummaryReplay
import app.batstats.battery.data.sampling.SessionReport
import app.batstats.battery.measurement.BatteryReading
import app.batstats.battery.measurement.CapacityConfidence
import app.batstats.battery.measurement.CurrentCalibration
import app.batstats.battery.measurement.HealthSummary
import app.batstats.settings.AppSettings
import app.batstats.settings.designCapacityOverrideMah
import app.batstats.settings.useFahrenheit
import app.batstats.ui.components.chart.ChartMath
import app.batstats.ui.components.chart.TimePoint
import app.batstats.ui.components.chart.TimeWindow
import io.github.mlmgames.settings.core.SettingsRepository
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// ---- UI state ----

/** SessionDetails: loading, the session, or why there is none. Plain values; the UI formats them. */
sealed interface SessionDetailsUiState {
    data object Loading : SessionDetailsUiState

    /** Not in history (deleted elsewhere, or removed by retention or a clear). */
    data object Missing : SessionDetailsUiState

    /** This screen deleted it: the screen navigates back. */
    data object Deleted : SessionDetailsUiState

    @Immutable
    data class Ready(
        val summary: SessionSummary,
        val charts: SessionCharts,
        /** Drain for a discharge session, charging insights for a charge session, else null. */
        val insights: SessionInsights?,
        /** Per-app usage; discharge sessions only. */
        val apps: SessionApps?,
        val useFahrenheit: Boolean,
        /** False while the session is recording (the writer would re-create it) or a delete is running. */
        val canDelete: Boolean,
        val deleteFailed: Boolean = false,
    ) : SessionDetailsUiState
}

/**
 * The header. [endedAtMs] is the end, the last saved reading, or (recording) the newest live one; [endLevel] follows
 * the same rule. Counter figures are null without counter data; [measured] is false for legacy rows.
 */
@Immutable
data class SessionSummary(
    val type: SessionType,
    val recording: Boolean,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val startLevel: Int?,
    val endLevel: Int?,
    /** Used (discharge) or added (charge), mAh. */
    val chargeMah: Double?,
    val energyWh: Double?,
    /** Average current magnitude over counter-covered time, mA. */
    val averageMa: Double?,
    /** Share of the observed time the charge counter covered, 0..1. */
    val counterCoverage: Double?,
    val capacity: SessionCapacity?,
    val measured: Boolean,
)

@Immutable
data class SessionCapacity(val mah: Int, val confidence: CapacityConfidence)

/**
 * Chart points over one shared [window] (start → end), so both charts scrub together: calibrated current (mA), level
 * (%) and temperature in the display unit. Null values are gap markers (monitoring restarted, an interruption, or a
 * missing value).
 */
@Immutable
data class SessionCharts(
    val window: TimeWindow?,
    val currentMa: List<TimePoint> = emptyList(),
    val level: List<TimePoint> = emptyList(),
    val temperature: List<TimePoint> = emptyList(),
) {
    val hasReadings: Boolean get() = currentMa.isNotEmpty() || level.isNotEmpty() || temperature.isNotEmpty()
}

sealed interface SessionInsights {
    @Immutable
    data class Drain(
        val screenOn: DrainState,
        val screenOff: DrainState,
        val deepSleepPercent: Double?,
        /** [deepSleepPercent] is over the screen-off time only (else over the whole session). */
        val deepSleepScreenOff: Boolean,
    ) : SessionInsights

    @Immutable
    data class Charging(
        val charger: ChargerType?,
        val averagePowerW: Double?,
        val peakPowerW: Double?,
        /** In the display unit (°C or °F). */
        val peakTemperature: Double?,
        /** How long the level took from 20 % to 80 %, when the session covered both. */
        val twentyToEightyMs: Long?,
    ) : SessionInsights
}

sealed interface SessionApps {
    /** Rows by power (top 30); [othersMah] is the folded tail. [soFar]: a recording session's live estimate. */
    @Immutable
    data class Ready(
        val rows: List<SessionApp>,
        val othersMah: Double?,
        val othersShare: Float,
        val basis: AppUsageBasis,
        val soFar: Boolean = false,
        val capturedAtMs: Long? = null,
    ) : SessionApps

    /** Recording: read at plug-in. */
    data object Pending : SessionApps

    /** Ended: the plug-in read is still running. */
    data object Collecting : SessionApps
    data object NoAccess : SessionApps
    data object Failed : SessionApps

    /** Legacy or imported rows, recorded before per-app usage existed. */
    data object NotRecorded : SessionApps

    /** Read, but no app used measurable power. */
    data object Empty : SessionApps
}

@Immutable
data class SessionApp(val uid: Int, val packageName: String, val label: AppLabel, val powerMah: Double, val share: Float)

sealed interface SessionDetailsEvent {
    data object Back : SessionDetailsEvent

    /** Confirmed in the dialog. */
    data object Delete : SessionDetailsEvent
    data object DismissDeleteError : SessionDetailsEvent
    data class OpenApp(val uid: Int, val packageName: String) : SessionDetailsEvent
}

// ---- Data seam ----

/** What SessionDetails reads and does: [DefaultSessionDetailsRepository] on device, a fake in unit tests. */
interface SessionDetailsRepository {
    fun session(id: String): Flow<ChargeSession?>

    /** The session's stored rows (raw current); re-emits at each save. */
    fun samples(id: String): Flow<List<BatterySample>>

    /** The stored per-app rows in rank order (top 30, then at most one "others" row). */
    fun appUsage(id: String): Flow<List<SessionAppUsage>>

    /** The generation the writer is recording, or null while monitoring is off (see [SessionEvidence.isRecording]). */
    val recordingGeneration: Flow<String?>

    /** The latest capture (2 s while the screen holds SamplingDemand). */
    val realtime: StateFlow<BatteryRepository.Realtime>
    val calibration: Flow<CurrentCalibration>
    val settings: Flow<AppSettings>

    /** The newest [limit] sessions, for the Health estimate when the session's own counter gives no capacity. */
    fun recentSessions(limit: Int): Flow<List<ChargeSession>>

    /** The last privileged dump's per-app usage, or null; reading it never starts a dump. */
    val cachedAppUsage: Flow<AppUsageSnapshot?>
    suspend fun baseline(sessionId: String): AppUsageSnapshot?

    /**
     * Deletes the session with its readings, per-app rows and snapshots, in one transaction. Refused (false) while it
     * is recording, while history is being cleared, or when it is already gone.
     */
    suspend fun deleteSession(id: String): Boolean
}

/**
 * [SessionDetailsRepository] over the app's repositories. The data layer has no per-session delete yet, so
 * [deleteSession] runs its statements here, serialized with imports and clears (see the P4b report for the DAO
 * method that should replace them).
 */
class DefaultSessionDetailsRepository(
    private val repository: BatteryRepository,
    private val database: BatteryDatabase,
    calibrationStore: CalibrationStore,
    appStats: AppStatsRepository,
    private val snapshots: SessionSnapshotStore,
    settingsRepository: SettingsRepository<AppSettings>,
    private val maintenance: HistoryMaintenance,
) : SessionDetailsRepository {
    override fun session(id: String) = database.sessionDao().session(id)
    override fun samples(id: String) = repository.samplesForSession(id)
    override fun appUsage(id: String) = database.appUsageDao().sessionUsage(id)
    override val recordingGeneration: Flow<String?> = combine(repository.isMonitoringFlow, repository.observation) { on, observation ->
        if (on && !observation.stopped) observation.latest?.generation else null
    }.distinctUntilChanged()
    override val realtime = repository.realtimeFlow
    override val calibration = calibrationStore.state.map { it.effective }.distinctUntilChanged()
    override val settings = settingsRepository.flow
    override fun recentSessions(limit: Int) = database.sessionDao().filteredSessions(null, "", limit)
    override val cachedAppUsage = appStats.cached.map { it?.toAppUsageSnapshot() }
    override suspend fun baseline(sessionId: String) = snapshots.baseline(sessionId)

    override suspend fun deleteSession(id: String): Boolean {
        if (repository.isClearingHistory) return false
        return maintenance.mutations.withLock {
            database.withTransaction {
                val row = database.sessionDao().byId(id) ?: return@withTransaction false
                // The writer rewrites its open row at every save: a recording session would come straight back.
                if (SessionEvidence.isRecording(row, currentGeneration())) return@withTransaction false
                database.appUsageDao().deleteSessionUsage(id)
                delete("DELETE FROM app_snapshots WHERE sessionId = ?", id)
                delete("DELETE FROM battery_samples WHERE sessionId = ?", id)
                delete("DELETE FROM charge_sessions WHERE sessionId = ?", id) > 0
            }
        }
    }

    private fun currentGeneration(): String? {
        val observation = repository.observation.value
        return if (repository.isMonitoringFlow.value && !observation.stopped) observation.latest?.generation else null
    }

    private fun delete(sql: String, id: String): Int = database.compileStatement(sql).use { statement ->
        statement.bindString(1, id)
        statement.executeUpdateDelete()
    }
}

// ---- Mapping ----

/** How [SessionDetailsViewModel] turns a session row and its readings into [SessionDetailsUiState] parts. Pure. */
internal object SessionDetailsMapping {
    const val DOWNSAMPLE_ABOVE = 2_400
    const val DOWNSAMPLE_BUCKETS = 600

    /** Live readings kept past the newest saved row; saves are ≤ 30 s apart while the screen is on. */
    const val LIVE_KEEP_MS = 10 * 60_000L

    /** Like ObservedBucket.rateMa: no average from less than a minute of counter data. */
    private const val MIN_COUNTER_MS = 60_000L
    private const val FROM_LEVEL = 20
    private const val TO_LEVEL = 80
    private const val NWH_PER_WH = 1_000_000_000.0

    /** What the live buffer of a recording session is keyed on (a save rewriting the row doesn't restart it). */
    data class LiveKey(val sessionId: String, val type: SessionType, val startTime: Long)

    /**
     * Adds a live capture to a recording session's buffer when it is this app's own reading, from this session (its
     * power state matches the session type; the writer's copy carries the session id), and newer than the last one.
     */
    fun appendLive(buffer: List<BatterySample>, sample: BatterySample?, key: LiveKey): List<BatterySample> {
        if (sample == null || sample.source != DailySummaryReplay.SAMPLE_SOURCE || sample.timestamp < key.startTime) return buffer
        if (sample.sessionId != null && sample.sessionId != key.sessionId) return buffer
        if (SessionReport.sessionType(BatteryReading.powerState(sample.status, sample.plugged)) != key.type) return buffer
        val last = buffer.lastOrNull()
        if (last != null && sample.timestamp <= last.timestamp) return buffer
        val cutoff = sample.timestamp - LIVE_KEEP_MS
        return (buffer + sample).filter { it.timestamp >= cutoff }
    }

    /** Stored rows in time order, then the live ones newer than the newest stored row. */
    fun merge(stored: List<BatterySample>, live: List<BatterySample>): List<BatterySample> {
        val sorted = stored.sortedBy { it.timestamp }
        val newest = sorted.lastOrNull()?.timestamp ?: Long.MIN_VALUE
        return sorted + live.filter { it.timestamp > newest }
    }

    fun summary(session: ChargeSession, recording: Boolean, readings: List<BatterySample>): SessionSummary {
        val latest = readings.lastOrNull()
        val measured = SessionEvidence.hasCoverage(session)
        val counter = measured && session.counterCoveredMs > 0
        val endMs = if (recording) {
            maxOf(latest?.timestamp ?: session.startTime, SessionEvidence.lastEvidence(session))
        } else {
            session.endTime ?: SessionEvidence.lastEvidence(session)
        }
        return SessionSummary(
            type = session.type,
            recording = recording,
            startedAtMs = session.startTime,
            endedAtMs = endMs.coerceAtLeast(session.startTime),
            startLevel = session.startLevel,
            endLevel = if (recording) latest?.levelPercent ?: session.endLevel else session.endLevel,
            chargeMah = session.deltaUah?.takeIf { counter }?.div(1_000.0),
            energyWh = session.energyNwh?.takeIf { counter }?.div(NWH_PER_WH),
            averageMa = session.avgCurrentUa?.takeIf { measured && session.counterCoveredMs >= MIN_COUNTER_MS }?.let { abs(it) / 1_000.0 },
            counterCoverage = if (measured && session.observedMs >= MIN_COUNTER_MS) {
                (session.counterCoveredMs.toDouble() / session.observedMs).coerceIn(0.0, 1.0)
            } else null,
            capacity = HealthSummary.storedEstimate(session.capacityEstimateMah, session.capacityConfidence, session.capacityBasis)
                ?.takeIf { measured }
                ?.let { SessionCapacity(it.fullMah, it.confidence) },
            measured = measured,
        )
    }

    /** The shared x range, start → end; null for a zero-length session (the charts then fit their data). */
    fun window(summary: SessionSummary): TimeWindow? =
        TimeWindow(summary.startedAtMs, summary.endedAtMs).takeIf { summary.endedAtMs > summary.startedAtMs }

    /**
     * Readings → calibrated mA, level and temperature points (raw rows are stored; the current calibration is
     * applied for display). A gap marker goes where monitoring restarted (a new observation id) or a row recorded an
     * interruption.
     */
    fun charts(readings: List<BatterySample>, calibration: CurrentCalibration, fahrenheit: Boolean, window: TimeWindow?): SessionCharts {
        val current = ArrayList<TimePoint>(readings.size)
        val level = ArrayList<TimePoint>(readings.size)
        val temperature = ArrayList<TimePoint>(readings.size)
        var previous: BatterySample? = null
        for (sample in readings) {
            val before = previous
            if (before != null && (before.observationId != sample.observationId || sample.boundaryReason != null)) {
                val gap = TimePoint((before.timestamp + sample.timestamp) / 2, null)
                current += gap
                level += gap
                temperature += gap
            }
            val time = sample.timestamp
            current += TimePoint(time, BatteryReading.calibratedUa(sample.currentNowUa, calibration)?.div(1_000.0))
            level += TimePoint(time, sample.levelPercent?.toDouble())
            temperature += TimePoint(time, sample.temperatureDeciC?.let { displayTemperature(it / 10.0, fahrenheit) })
            previous = sample
        }
        return SessionCharts(window, current, level, temperature)
    }

    /**
     * Screen on / off drain and deep sleep from the row ([SessionDrain], the rule Now's "Since unplug" uses). Deep
     * sleep is over the screen-off time when the row has it, else over the whole session. Null for legacy rows.
     */
    fun drain(session: ChargeSession, fullUah: Long?): SessionInsights.Drain? {
        if (!SessionEvidence.hasCoverage(session)) return null
        val drain = SessionDrain.of(session, fullUah)
        val screenOffSleep = session.screenOffSuspendMs
            ?.takeIf { session.screenOffMs > 0 }
            ?.let { (it * 100.0 / session.screenOffMs).coerceIn(0.0, 100.0) }
        return SessionInsights.Drain(
            screenOn = DrainState(drain.screenOn.durationMs, drain.screenOn.currentMa, drain.screenOn.percentPerHour),
            screenOff = DrainState(drain.screenOff.durationMs, drain.screenOff.currentMa, drain.screenOff.percentPerHour),
            deepSleepPercent = screenOffSleep ?: drain.deepSleepPercent,
            deepSleepScreenOff = screenOffSleep != null,
        )
    }

    /** The fuel gauge's full charge from the newest reading that allows it (for %/h); else null. */
    fun counterFullUah(readings: List<BatterySample>): Long? =
        readings.asReversed().firstNotNullOfOrNull { HealthSummary.counterFullUah(it.chargeCounterUah, it.levelPercent) }

    /**
     * Charging insights: the charger (stored enum name, else the readings' plug), the average power (energy added over
     * counter-covered time, else the readings' time-weighted mean), peak power and temperature, and the 20 → 80 time.
     */
    fun charging(session: ChargeSession, readings: List<BatterySample>, calibration: CurrentCalibration, fahrenheit: Boolean): SessionInsights.Charging {
        val measured = SessionEvidence.hasCoverage(session)
        val fromEnergy = session.energyNwh
            ?.takeIf { measured && session.counterCoveredMs >= MIN_COUNTER_MS }
            ?.let { it / NWH_PER_WH * MS_PER_HOUR / session.counterCoveredMs }
        val peakC = session.peakTemperatureDeciC?.div(10.0) ?: readings.mapNotNull { it.temperatureDeciC }.maxOrNull()?.div(10.0)
        return SessionInsights.Charging(
            charger = ChargerType.entries.firstOrNull { it.name == session.chargerType }
                ?: readings.firstNotNullOfOrNull { ChargerType.of(it.plugged) },
            averagePowerW = fromEnergy ?: meanPowerW(readings, calibration),
            peakPowerW = session.peakPowerMw?.div(1_000.0),
            peakTemperature = peakC?.let { displayTemperature(it, fahrenheit) },
            twentyToEightyMs = levelSpanMs(readings, FROM_LEVEL, TO_LEVEL),
        )
    }

    /** Time-weighted mean of calibrated I × V between consecutive readings of one observation, in W. */
    fun meanPowerW(readings: List<BatterySample>, calibration: CurrentCalibration): Double? {
        var energy = 0.0
        var duration = 0L
        readings.zipWithNext { a, b ->
            val dt = b.timestamp - a.timestamp
            val pa = BatteryReading.powerMw(BatteryReading.calibratedUa(a.currentNowUa, calibration), a.voltageMv)
            val pb = BatteryReading.powerMw(BatteryReading.calibratedUa(b.currentNowUa, calibration), b.voltageMv)
            if (dt > 0 && pa != null && pb != null && a.observationId == b.observationId && b.boundaryReason == null) {
                energy += (pa + pb) / 2 * dt
                duration += dt
            }
        }
        return if (duration > 0) energy / duration / 1_000 else null
    }

    /**
     * From the moment the level first reached [from] to the moment it first reached [to]; only when the session
     * started at or below [from] (else it began part-way).
     */
    fun levelSpanMs(readings: List<BatterySample>, from: Int, to: Int): Long? {
        val levels = readings.mapNotNull { sample -> sample.levelPercent?.let { sample.timestamp to it } }
        val first = levels.firstOrNull() ?: return null
        if (first.second > from) return null
        val start = levels.firstOrNull { it.second >= from } ?: return null
        val end = levels.firstOrNull { it.first >= start.first && it.second >= to } ?: return null
        return end.first - start.first
    }

    /** Per-app status → what the Apps panel shows (rows need labels, so [SessionDetailsViewModel] builds Ready). */
    fun appsStatus(status: AppUsageStatus?, open: Boolean): SessionApps? = when (status) {
        AppUsageStatus.READY -> null
        // A row left open by a stopped process never gets its plug-in read (the collector marks it FAILED).
        AppUsageStatus.PENDING -> if (open) SessionApps.Failed else SessionApps.Collecting
        AppUsageStatus.NO_ACCESS -> SessionApps.NoAccess
        AppUsageStatus.FAILED -> SessionApps.Failed
        AppUsageStatus.NOT_APPLICABLE, null -> SessionApps.NotRecorded
    }

    fun displayTemperature(celsius: Double, fahrenheit: Boolean): Double = if (fahrenheit) celsius * 9 / 5 + 32 else celsius

    private const val MS_PER_HOUR = 3_600_000.0
}

// ---- ViewModel ----

/**
 * One session: header stats, level + current and temperature charts, drain (discharge) or charging insights (charge),
 * per-app usage (discharge), and Delete. Everything is derived from flows while the screen collects [state] (stopped
 * 5 s after it leaves), on [computeDispatcher]. While the session is recording, live captures (2 s while the screen
 * holds SamplingDemand) are appended after the newest saved row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailsViewModel(
    private val source: SessionDetailsRepository,
    private val appInfo: AppInfoSource,
    private val sessionId: String,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private enum class Deletion { IDLE, RUNNING, DONE, FAILED }

    private val deletion = MutableStateFlow(Deletion.IDLE)
    private val session: Flow<ChargeSession?> = source.session(sessionId).distinctUntilChanged()

    private val recording: Flow<Boolean> = combine(session, source.recordingGeneration) { row, generation ->
        row != null && SessionEvidence.isRecording(row, generation)
    }.distinctUntilChanged()

    private val live: Flow<List<BatterySample>> = combine(session, recording) { row, on ->
        row?.takeIf { on }?.let { SessionDetailsMapping.LiveKey(it.sessionId, it.type, it.startTime) }
    }
        .distinctUntilChanged()
        .flatMapLatest { key ->
            if (key == null) {
                flowOf(emptyList())
            } else {
                source.realtime.scan(emptyList()) { buffer, reading -> SessionDetailsMapping.appendLive(buffer, reading.sample, key) }
            }
        }

    private val readings: Flow<List<BatterySample>> = combine(source.samples(sessionId), live, SessionDetailsMapping::merge)

    private val healthFullUah: Flow<Long?> = combine(
        source.recentSessions(HealthSummary.SESSIONS),
        source.settings.map { it.designCapacityOverrideMah }.distinctUntilChanged(),
    ) { sessions, designMah ->
        HealthSummary.of(
            sessions.mapNotNull { HealthSummary.storedEstimate(it.capacityEstimateMah, it.capacityConfidence, it.capacityBasis) },
            designMah,
        )?.estimate?.fullUah
    }.distinctUntilChanged()

    private data class AppsKey(val type: SessionType, val status: AppUsageStatus?, val basis: AppUsageBasis?, val open: Boolean)

    private val apps: Flow<SessionApps?> = combine(
        session.map { row -> row?.let { AppsKey(it.type, it.appUsageStatus, it.appUsageBasis, it.endTime == null) } }.distinctUntilChanged(),
        recording,
        source.appUsage(sessionId),
        source.cachedAppUsage,
    ) { key, on, rows, cached -> AppsInput(key, on, rows, cached) }
        .mapLatest(::appsFor)

    private class AppsInput(val key: AppsKey?, val recording: Boolean, val rows: List<SessionAppUsage>, val cached: AppUsageSnapshot?)

    private class Inputs(
        val session: ChargeSession?,
        val recording: Boolean,
        val readings: List<BatterySample>,
        val calibration: CurrentCalibration,
        val fahrenheit: Boolean,
    )

    private class Parts(val inputs: Inputs, val healthFullUah: Long?, val apps: SessionApps?, val deletion: Deletion)

    val state: StateFlow<SessionDetailsUiState> = combine(
        combine(
            session,
            recording,
            readings,
            source.calibration,
            source.settings.map { it.useFahrenheit }.distinctUntilChanged(),
            ::Inputs,
        ),
        healthFullUah,
        apps,
        deletion,
        ::Parts,
    )
        .mapLatest(::build)
        .flowOn(computeDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SessionDetailsUiState.Loading)

    /** State changes; navigation events are the screen's. */
    fun onEvent(event: SessionDetailsEvent) {
        when (event) {
            SessionDetailsEvent.Delete -> delete()
            SessionDetailsEvent.DismissDeleteError -> deletion.compareAndSet(Deletion.FAILED, Deletion.IDLE)
            SessionDetailsEvent.Back, is SessionDetailsEvent.OpenApp -> Unit
        }
    }

    private fun delete() {
        val ready = state.value as? SessionDetailsUiState.Ready ?: return
        if (!ready.canDelete) return
        val started = deletion.compareAndSet(Deletion.IDLE, Deletion.RUNNING) || deletion.compareAndSet(Deletion.FAILED, Deletion.RUNNING)
        if (!started) return
        viewModelScope.launch {
            // A confirmed delete finishes even if the screen leaves meanwhile.
            val deleted = withContext(NonCancellable) {
                try {
                    source.deleteSession(sessionId)
                } catch (e: Exception) {
                    false
                }
            }
            deletion.value = if (deleted) Deletion.DONE else Deletion.FAILED
        }
    }

    private suspend fun build(parts: Parts): SessionDetailsUiState {
        val inputs = parts.inputs
        val row = inputs.session
        // Room can report the row gone before the delete call returns: that is still this screen's delete.
        if (parts.deletion == Deletion.DONE || (parts.deletion == Deletion.RUNNING && row == null)) return SessionDetailsUiState.Deleted
        if (row == null) return SessionDetailsUiState.Missing
        val summary = SessionDetailsMapping.summary(row, inputs.recording, inputs.readings)
        val charts = SessionDetailsMapping.charts(inputs.readings, inputs.calibration, inputs.fahrenheit, SessionDetailsMapping.window(summary))
        val insights = when (row.type) {
            SessionType.DISCHARGE -> SessionDetailsMapping.drain(row, SessionDetailsMapping.counterFullUah(inputs.readings) ?: parts.healthFullUah)
            SessionType.CHARGE -> SessionDetailsMapping.charging(row, inputs.readings, inputs.calibration, inputs.fahrenheit)
            SessionType.PLUGGED, SessionType.UNKNOWN -> null
        }
        return SessionDetailsUiState.Ready(
            summary = summary,
            charts = charts.copy(
                currentMa = downsample(charts.currentMa),
                level = downsample(charts.level),
                temperature = downsample(charts.temperature),
            ),
            insights = insights,
            apps = parts.apps,
            useFahrenheit = inputs.fahrenheit,
            canDelete = !inputs.recording && parts.deletion != Deletion.RUNNING,
            deleteFailed = parts.deletion == Deletion.FAILED,
        )
    }

    private suspend fun downsample(points: List<TimePoint>): List<TimePoint> =
        if (points.size <= SessionDetailsMapping.DOWNSAMPLE_ABOVE) points
        else ChartMath.downsampleMinMaxAsync(points, SessionDetailsMapping.DOWNSAMPLE_BUCKETS, computeDispatcher)

    /**
     * Discharge sessions only. Stored rows once READY; while recording, the cached dump since this session's baseline
     * (never a new dump), when it is newer than the baseline; otherwise the status says why there are none.
     */
    private suspend fun appsFor(input: AppsInput): SessionApps? {
        val key = input.key ?: return null
        if (key.type != SessionType.DISCHARGE) return null
        if (input.recording) {
            val cached = input.cached ?: return SessionApps.Pending
            val baseline = source.baseline(sessionId)?.takeIf { cached.capturedAt > it.capturedAt } ?: return SessionApps.Pending
            val result = AppUsageDelta.compute(baseline, cached)
            return ready(result.rows, result.basis, soFar = true, capturedAtMs = cached.capturedAt)
        }
        SessionDetailsMapping.appsStatus(key.status, key.open)?.let { return it }
        val basis = key.basis ?: input.rows.firstOrNull()?.basis ?: return SessionApps.Empty
        return ready(input.rows.map { it.toRow() }, basis, soFar = false, capturedAtMs = null)
    }

    private suspend fun ready(rows: List<AppUsageRow>, basis: AppUsageBasis, soFar: Boolean, capturedAtMs: Long?): SessionApps {
        val total = rows.sumOf { it.powerMah.coerceAtLeast(0.0) }
        val apps = rows.filter { !it.isOthers && it.powerMah > 0 }
        if (total <= 0 || apps.isEmpty()) return SessionApps.Empty
        val others = rows.filter { it.isOthers }.sumOf { it.powerMah.coerceAtLeast(0.0) }
        return SessionApps.Ready(
            rows = apps.map { row ->
                SessionApp(row.uid, row.packageName, AppLabel.of(row.uid, row.packageName, info(row.packageName)), row.powerMah, (row.powerMah / total).toFloat())
            },
            othersMah = others.takeIf { it > 0 },
            othersShare = (others / total).toFloat(),
            basis = basis,
            soFar = soFar,
            capturedAtMs = capturedAtMs,
        )
    }

    private suspend fun info(packageName: String): AppInfo? = try {
        appInfo.info(packageName)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
