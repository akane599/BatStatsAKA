package app.batstats.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.batstats.battery.apps.AppInfoSource
import app.batstats.battery.apps.AppStatsRepository
import app.batstats.battery.apps.AppUsageDelta
import app.batstats.battery.apps.AppUsageDeltaResult
import app.batstats.battery.apps.AppUsageSnapshot
import app.batstats.battery.apps.SessionSnapshotStore
import app.batstats.battery.apps.toAppUsageSnapshot
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.CalibrationStore
import app.batstats.battery.data.db.BatteryDatabase
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.DailySummary
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.data.sampling.ChargerType
import app.batstats.battery.data.sampling.DailySummaryReplay
import app.batstats.battery.measurement.BatteryReading
import app.batstats.battery.measurement.CalibrationState
import app.batstats.battery.measurement.CapacityBasis
import app.batstats.battery.measurement.CapacityConfidence
import app.batstats.battery.measurement.CapacityEstimate
import app.batstats.battery.measurement.CapacityEstimator
import app.batstats.battery.measurement.CurrentCalibration
import app.batstats.battery.measurement.DailySummaryAggregator
import app.batstats.battery.measurement.EtaBasis
import app.batstats.battery.measurement.ObservationSummary
import app.batstats.battery.measurement.ObservedBucket
import app.batstats.battery.measurement.PowerState
import app.batstats.battery.service.MonitoringControl
import app.batstats.settings.AppSettings
import app.batstats.settings.designCapacityOverrideMah
import app.batstats.settings.useFahrenheit
import app.batstats.ui.components.chart.ChartMath
import app.batstats.ui.components.chart.TimePoint
import app.batstats.ui.components.chart.TimeWindow
import app.batstats.ui.screens.now.DrainState
import app.batstats.ui.screens.now.Eta
import app.batstats.ui.screens.now.HealthState
import app.batstats.ui.screens.now.HeroState
import app.batstats.ui.screens.now.NowEvent
import app.batstats.ui.screens.now.NowUiState
import app.batstats.ui.screens.now.Readouts
import app.batstats.ui.screens.now.SinceUnplugState
import app.batstats.ui.screens.now.TodayState
import app.batstats.ui.screens.now.TopApp
import app.batstats.ui.screens.now.TopAppsState
import app.batstats.ui.screens.now.TraceRange
import app.batstats.ui.screens.now.TraceState
import io.github.mlmgames.settings.core.SettingsRepository
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn

/**
 * What the Now screen reads and does, as one seam: [DefaultNowRepository] on device, a fake in unit tests. Nothing
 * here starts privileged work: per-app data is only the last cached dump (the Apps screen fetches on demand).
 */
interface NowRepository {
    /** The latest capture, calibrated; every 2 s while the screen holds SamplingDemand. */
    val realtime: StateFlow<BatteryRepository.Realtime>
    val observation: StateFlow<ObservationSummary>
    val calibration: StateFlow<CalibrationState>
    val settings: Flow<AppSettings>

    /** The last privileged dump's per-app usage, or null; reading it never starts a dump. */
    val cachedAppUsage: Flow<AppUsageSnapshot?>
    val activeSession: Flow<ChargeSession?>

    /** Stored samples from [fromMs] on, oldest first; re-emits when rows are saved. */
    fun samplesSince(fromMs: Long): Flow<List<BatterySample>>
    fun day(epochDay: Long): Flow<DailySummary?>

    /** The newest [limit] sessions, newest first. */
    fun recentSessions(limit: Int): Flow<List<ChargeSession>>

    /** The session's BASELINE snapshot, when one was captured. */
    suspend fun baseline(sessionId: String): AppUsageSnapshot?
    fun resetObservation()
    fun undoCalibration()
    fun dismissCalibrationNotice()
}

/** [NowRepository] over the app's repositories; every read is a Flow or a main-safe suspend call. */
class DefaultNowRepository(
    private val repository: BatteryRepository,
    private val database: BatteryDatabase,
    private val calibrationStore: CalibrationStore,
    appStats: AppStatsRepository,
    private val snapshots: SessionSnapshotStore,
    settingsRepository: SettingsRepository<AppSettings>,
) : NowRepository {
    override val realtime = repository.realtimeFlow
    override val observation = repository.observation
    override val calibration = calibrationStore.state
    override val settings = settingsRepository.flow
    override val cachedAppUsage = appStats.cached.map { it?.toAppUsageSnapshot() }
    override val activeSession = repository.activeSessionFlow
    override fun samplesSince(fromMs: Long) = repository.samplesBetween(fromMs, Long.MAX_VALUE)
    override fun day(epochDay: Long) = database.dailySummaryDao().day(epochDay)
    override fun recentSessions(limit: Int) = repository.sessionDao.filteredSessions(null, "", limit)
    override suspend fun baseline(sessionId: String) = snapshots.baseline(sessionId)
    override fun resetObservation() = repository.resetObservation()
    override fun undoCalibration() = calibrationStore.undoLastCorrection()
    override fun dismissCalibrationNotice() = calibrationStore.dismissNotice()
}

/**
 * Now: the hero (level, state, ETA, Start/Stop), the current trace, live readouts, the monitoring window's drain,
 * and the Today / Health / Top apps cards. Everything is derived from flows while the screen collects [state]
 * (stopped 5 s after it leaves), on [computeDispatcher]; the trace is downsampled there too.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowViewModel(
    private val source: NowRepository,
    private val monitoring: MonitoringControl,
    private val appInfo: AppInfoSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val range = MutableStateFlow(TraceRange.LIVE)
    private val startBlocked = MutableStateFlow(false)

    private class LiveBlock(val hero: HeroState, val readouts: Readouts)
    private class Rows(val endMs: Long, val samples: List<BatterySample>)
    private class Cards(val today: TodayState?, val health: HealthEstimate?, val topApps: TopAppsState, val calibration: CalibrationState)
    private class Now(val live: LiveBlock, val trace: TraceState, val observation: ObservationSummary, val fahrenheit: Boolean)

    private val live: Flow<LiveBlock> = combine(
        source.realtime.scan(NowMath.ReadingEta()) { previous, reading -> NowMath.withEta(previous, reading) },
        monitoring.isMonitoring,
        startBlocked,
    ) { reading, on, blocked -> LiveBlock(NowMath.hero(reading, on, blocked), NowMath.readouts(reading.reading)) }

    private val trace: Flow<TraceState> = range.flatMapLatest { selected ->
        val rows = if (selected == TraceRange.LIVE) liveRows() else historyRows(selected)
        combine(rows, source.calibration.map { it.effective }.distinctUntilChanged()) { window, calibration ->
            NowMath.trace(selected, window.samples, window.endMs, calibration)
        }.mapLatest { trace ->
            if (trace.points.size <= NowMath.DOWNSAMPLE_ABOVE) trace
            else trace.copy(points = ChartMath.downsampleMinMaxAsync(trace.points, NowMath.DOWNSAMPLE_BUCKETS, computeDispatcher))
        }
    }

    // Re-evaluated at every reading (2 s while visible), so the card moves to the new day after midnight.
    private val today: Flow<TodayState?> = source.realtime
        .map { DailySummaryAggregator.epochDay(clock(), zone()) }
        .distinctUntilChanged()
        .flatMapLatest { day -> source.day(day) }
        .map { row -> row?.let(NowMath::today) }

    private val health: Flow<HealthEstimate?> = combine(
        source.recentSessions(NowMath.HEALTH_SESSIONS),
        source.settings.map { it.designCapacityOverrideMah }.distinctUntilChanged(),
    ) { sessions, designMah -> NowMath.health(sessions, designMah) }

    private val topApps: Flow<TopAppsState> = combine(
        source.cachedAppUsage,
        source.activeSession.map { session -> session?.takeIf { it.type == SessionType.DISCHARGE }?.sessionId }.distinctUntilChanged(),
    ) { usage, sessionId -> usage to sessionId }
        .mapLatest { (usage, sessionId) -> topApps(usage, sessionId) }

    val state: StateFlow<NowUiState> = combine(
        combine(live, trace, source.observation, source.settings.map { it.useFahrenheit }.distinctUntilChanged(), ::Now),
        combine(today, health, topApps, source.calibration, ::Cards),
    ) { now, cards ->
        NowUiState(
            hero = now.live.hero,
            readouts = now.live.readouts,
            trace = now.trace,
            sinceUnplug = NowMath.sinceUnplug(now.observation, cards.health?.estimate?.fullUah),
            today = cards.today,
            health = cards.health?.state,
            topApps = cards.topApps,
            calibrationNotice = NowMath.notice(cards.calibration),
            useFahrenheit = now.fahrenheit,
        )
    }
        .flowOn(computeDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), NowUiState())

    /** State changes; navigation events are the screen's. */
    fun onEvent(event: NowEvent) {
        when (event) {
            NowEvent.ToggleMonitoring -> toggleMonitoring()
            is NowEvent.SelectRange -> range.value = event.range
            NowEvent.ResetObservation -> source.resetObservation()
            NowEvent.UndoCalibration -> source.undoCalibration()
            NowEvent.KeepCalibration -> source.dismissCalibrationNotice()
            NowEvent.OpenHistory, NowEvent.OpenHealth, NowEvent.OpenApps, is NowEvent.OpenApp -> Unit
        }
    }

    private fun toggleMonitoring() {
        if (monitoring.isMonitoring.value) {
            startBlocked.value = false
            monitoring.stop()
        } else {
            // BLOCKED: Android refused the foreground-service start (MonitoringControl); say so and keep the button.
            startBlocked.value = monitoring.start() == MonitoringControl.StartResult.BLOCKED
        }
    }

    /** Trailing [TraceRange.LIVE] window: stored rows seed it, then every realtime reading is appended. */
    private fun liveRows(): Flow<Rows> = flow {
        val buffer = ArrayList<BatterySample>()
        source.samplesSince(clock() - TraceRange.LIVE.spanMs).first().forEach { NowMath.appendLive(buffer, it) }
        emit(Rows(buffer.lastOrNull()?.timestamp ?: clock(), buffer.toList()))
        source.realtime.collect { reading ->
            val sample = reading.sample ?: return@collect
            if (NowMath.appendLive(buffer, sample)) emit(Rows(sample.timestamp, buffer.toList()))
        }
    }

    /** Stored rows for [selected]; the window ends at the time of each emission (a save, ≥ 30 s apart). */
    private fun historyRows(selected: TraceRange): Flow<Rows> = flow {
        emitAll(source.samplesSince(clock() - selected.spanMs).map { rows -> Rows(clock(), rows) })
    }

    private suspend fun topApps(usage: AppUsageSnapshot?, dischargeSessionId: String?): TopAppsState {
        if (usage == null) return TopAppsState.Empty
        // Since unplug only when the dump is newer than this session's baseline (else it predates the session).
        val baseline = dischargeSessionId?.let { source.baseline(it) }?.takeIf { usage.capturedAt > it.capturedAt }
        val result = AppUsageDelta.compute(baseline, usage)
        val labels = NowMath.topRows(result).associate { it.packageName to label(it.packageName) }
        return NowMath.topApps(result, usage.capturedAt, labels)
    }

    private suspend fun label(packageName: String): String = try {
        appInfo.info(packageName).label
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        packageName
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** The combined capacity estimate (for %/h) and what the Health card shows. */
internal class HealthEstimate(val estimate: CapacityEstimate, val state: HealthState)

/** Pure mapping behind [NowViewModel]; unit-tested through it. */
internal object NowMath {
    const val HEALTH_SESSIONS = 50
    const val TOP_APPS = 3
    const val DOWNSAMPLE_ABOVE = 2_400
    const val DOWNSAMPLE_BUCKETS = 600

    /** The live trace breaks where readings are further apart than 3 screen-on polls (the app was away). */
    const val LIVE_MAX_GAP_MS = 95_000L

    /** A capture without an ETA keeps the last one this long (the writer adds it just after the capture). */
    const val ETA_HOLD_MS = 60_000L

    private const val MIN_LEVEL_FOR_CAPACITY = 10

    /** A reading with the ETA it shows: the newest one, or the previous one held across a capture without it. */
    data class ReadingEta(
        val reading: BatteryRepository.Realtime = BatteryRepository.Realtime(),
        val eta: HeldEta? = null,
    )

    data class HeldEta(val remainingMs: Long, val basis: EtaBasis?, val power: PowerState, val atMs: Long)

    /**
     * Realtime first carries the raw capture, then the writer's copy with `etaMs`; the next capture drops it again.
     * Keep the last estimate while the power state holds, for [ETA_HOLD_MS], counted down to this reading.
     */
    fun withEta(previous: ReadingEta, reading: BatteryRepository.Realtime): ReadingEta {
        val sample = reading.sample ?: return previous.copy(reading = reading)
        val power = reading.powerState
        val eta = sample.etaMs?.takeIf { it > 0 }
        val held = when {
            eta != null -> HeldEta(eta, EtaBasis.entries.firstOrNull { it.name == sample.etaBasis }, power, sample.timestamp)
            else -> previous.eta?.takeIf { it.power == power && sample.timestamp - it.atMs in 0..ETA_HOLD_MS }
        }
        return ReadingEta(reading, held)
    }

    fun hero(reading: ReadingEta, monitoring: Boolean, startBlocked: Boolean): HeroState {
        val realtime = reading.reading
        val sample = realtime.sample
        val eta = if (monitoring && sample != null) {
            reading.eta?.let { held -> Eta((held.remainingMs - (sample.timestamp - held.atMs)).coerceAtLeast(0), held.basis) }
        } else null
        return HeroState(
            hasReading = sample != null,
            level = realtime.level,
            power = realtime.powerState,
            charger = ChargerType.of(realtime.plugged),
            eta = eta,
            monitoring = monitoring,
            startBlocked = startBlocked && !monitoring,
        )
    }

    fun readouts(reading: BatteryRepository.Realtime) = Readouts(
        currentMa = reading.currentMa,
        powerW = reading.powerMw?.div(1_000),
        temperatureC = reading.temperatureC?.toDouble(),
        voltageV = reading.voltageMv?.div(1_000.0),
    )

    /** Adds [sample] when newer than the last one and drops what fell out of the live window; true if added. */
    fun appendLive(buffer: MutableList<BatterySample>, sample: BatterySample): Boolean {
        if (sample.source != DailySummaryReplay.SAMPLE_SOURCE) return false
        val last = buffer.lastOrNull()
        if (last != null && sample.timestamp <= last.timestamp) return false
        buffer += sample
        val cutoff = sample.timestamp - TraceRange.LIVE.spanMs
        buffer.removeAll { it.timestamp < cutoff }
        return true
    }

    /**
     * Rows → calibrated mA points in [endMs]'s window (plus one row before it, so the line reaches the edge). A gap
     * marker goes where monitoring restarted (a new observation id) or a row recorded an interruption.
     */
    fun trace(range: TraceRange, samples: List<BatterySample>, endMs: Long, calibration: CurrentCalibration): TraceState {
        val startMs = endMs - range.spanMs
        val live = samples.filter { it.source == DailySummaryReplay.SAMPLE_SOURCE }
        val first = (live.indexOfFirst { it.timestamp >= startMs }.takeIf { it >= 0 } ?: live.size).minus(1).coerceAtLeast(0)
        val points = ArrayList<TimePoint>()
        var previous: BatterySample? = null
        for (sample in live.subList(first, live.size)) {
            val before = previous
            if (before != null && (before.observationId != sample.observationId || sample.boundaryReason != null)) {
                points += TimePoint((before.timestamp + sample.timestamp) / 2, null)
            }
            points += TimePoint(sample.timestamp, BatteryReading.calibratedUa(sample.currentNowUa, calibration)?.div(1_000.0))
            previous = sample
        }
        return TraceState(
            range = range,
            points = points,
            window = TimeWindow(startMs, endMs),
            maxGapMs = if (range == TraceRange.LIVE) LIVE_MAX_GAP_MS else null,
        )
    }

    /**
     * %/h = average drain ÷ full capacity: the counter's own full charge (counter × 100 ÷ level) when plausible,
     * else the combined session estimate.
     */
    fun sinceUnplug(summary: ObservationSummary, estimateFullUah: Long?): SinceUnplugState? {
        val startedAt = summary.startedAt ?: return null
        val latest = summary.latest
        val counterFullUah = if (latest?.chargeUah != null && latest.level != null && latest.level >= MIN_LEVEL_FOR_CAPACITY) {
            (latest.chargeUah * 100 / latest.level).takeIf { it in CapacityEstimator.PLAUSIBLE_FULL_UAH }
        } else null
        val fullUah = counterFullUah ?: estimateFullUah
        return SinceUnplugState(
            startedAtMs = startedAt,
            throughMs = latest?.wallMs,
            screenOn = drain(summary.screenOn, fullUah),
            screenOff = drain(summary.screenOff, fullUah),
            deepSleepPercent = if (summary.cpuObservedMs > 0) summary.cpuSuspendMs * 100.0 / summary.cpuObservedMs else null,
            paused = summary.stopped,
        )
    }

    private fun drain(bucket: ObservedBucket, fullUah: Long?): DrainState {
        val rateMa = bucket.rateMa
        return DrainState(
            durationMs = bucket.durationMs,
            currentMa = rateMa,
            percentPerHour = if (rateMa != null && fullUah != null && fullUah > 0) rateMa * 1_000 * 100 / fullUah else null,
        )
    }

    fun today(row: DailySummary) = TodayState(
        usedMah = (row.screenOnDischargeUah + row.screenOffDischargeUah) / 1_000.0,
        chargedMah = row.chargedUah / 1_000.0,
        screenOnMs = row.screenOnMs,
    )

    /** The confidence-weighted median of the newest sessions' estimates (stored names parsed tolerantly). */
    fun health(sessions: List<ChargeSession>, designOverrideMah: Int): HealthEstimate? {
        val estimates = sessions.mapNotNull { session ->
            val mah = session.capacityEstimateMah ?: return@mapNotNull null
            val confidence = CapacityConfidence.entries.firstOrNull { it.name == session.capacityConfidence } ?: return@mapNotNull null
            val basis = CapacityBasis.entries.firstOrNull { it.name == session.capacityBasis } ?: CapacityBasis.COUNTER_SPAN
            CapacityEstimate(mah * 1_000L, confidence, basis)
        }
        val combined = CapacityEstimator.combine(estimates) ?: return null
        val design = CapacityEstimator.designUah(designOverrideMah, null)
        return HealthEstimate(
            combined,
            HealthState(combined.fullMah, combined.confidence, CapacityEstimator.healthPercent(combined.fullUah, design)),
        )
    }

    /** The rows Top apps shows: the biggest real apps (the folded "others" row only counts toward the total). */
    fun topRows(result: AppUsageDeltaResult) = result.rows.filter { !it.isOthers && it.powerMah > 0 }.take(TOP_APPS)

    fun topApps(result: AppUsageDeltaResult, capturedAtMs: Long, labels: Map<String, String>): TopAppsState {
        val total = result.rows.sumOf { it.powerMah.coerceAtLeast(0.0) }
        val rows = topRows(result)
        if (total <= 0 || rows.isEmpty()) return TopAppsState.Empty
        return TopAppsState.Ready(
            rows = rows.map { row ->
                TopApp(row.uid, row.packageName, labels[row.packageName] ?: row.packageName, row.powerMah, (row.powerMah / total).toFloat())
            },
            basis = result.basis,
            capturedAtMs = capturedAtMs,
        )
    }

    fun notice(state: CalibrationState): CurrentCalibration? =
        if (state.noticePending) state.detected ?: state.effective else null
}
