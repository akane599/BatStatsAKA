package app.batstats.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.batstats.battery.apps.AppInfo
import app.batstats.battery.apps.AppInfoSource
import app.batstats.battery.apps.AppLabel
import app.batstats.battery.apps.AppUsageSnapshot
import app.batstats.battery.apps.TopApps
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.data.uah
import app.batstats.battery.measurement.CalibrationState
import app.batstats.battery.measurement.DailySummaryAggregator
import app.batstats.battery.measurement.HealthSummary
import app.batstats.battery.service.MonitoringControl
import app.batstats.settings.useFahrenheit
import app.batstats.ui.components.chart.ChartMath
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
 * Now: the hero (level, state, ETA, Start/Stop), the current trace, live readouts, the on-battery window's drain,
 * and the Today / Health / Top apps cards. Everything is derived from flows while the screen collects [state]
 * (stopped 5 s after it leaves), on [computeDispatcher]; the trace is downsampled there too. The mapping rules are
 * in [NowMapping]; the only UI-package types used are the chart's data points, since ChartMath downsamples them.
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

    private class Live(val hero: HeroState, val readouts: Readouts, val nowMs: Long, val counterFullUah: Long?)
    private class Rows(val endMs: Long, val samples: List<BatterySample>)
    private class Now(val live: Live, val trace: TraceState, val fahrenheit: Boolean, val calibration: CalibrationState)
    private class Cards(val today: TodayState?, val health: HealthSummary?, val topApps: TopAppsState, val onBattery: ChargeSession?)

    private val live: Flow<Live> = combine(
        source.realtime.scan(NowMapping.ReadingEta()) { previous, reading -> NowMapping.withEta(previous, reading) },
        monitoring.isMonitoring,
        startBlocked,
    ) { reading, on, blocked ->
        Live(
            hero = NowMapping.hero(reading, on, blocked),
            readouts = NowMapping.readouts(reading.reading),
            nowMs = reading.reading.sample?.timestamp ?: clock(),
            counterFullUah = NowMapping.counterFullUah(reading.reading),
        )
    }

    private val trace: Flow<TraceState> = range.flatMapLatest { selected ->
        val rows = if (selected == TraceRange.LIVE) liveRows() else historyRows(selected)
        combine(rows, source.calibration.map { it.effective }.distinctUntilChanged()) { window, calibration ->
            NowMapping.trace(selected, window.samples, window.endMs, calibration)
        }.mapLatest { trace ->
            if (trace.points.size <= NowMapping.DOWNSAMPLE_ABOVE) trace
            else trace.copy(points = ChartMath.downsampleMinMaxAsync(trace.points, NowMapping.DOWNSAMPLE_BUCKETS, computeDispatcher))
        }
    }

    // Re-evaluated at every reading (2 s while visible), so the card moves to the new day after midnight.
    private val today: Flow<TodayState?> = source.realtime
        .map { DailySummaryAggregator.epochDay(clock(), zone()) }
        .distinctUntilChanged()
        .flatMapLatest { day -> source.day(day) }
        .map { row -> row?.let(NowMapping::today) }

    private val health: Flow<HealthSummary?> = combine(
        source.recentSessions(HealthSummary.SESSIONS),
        source.design.map { it.uah }.distinctUntilChanged(),
    ) { sessions, designUah -> NowMapping.healthSummary(sessions, designUah) }

    private val topApps: Flow<TopAppsState> = combine(
        source.cachedAppUsage,
        source.activeSession.map { session -> session?.takeIf { it.type == SessionType.DISCHARGE }?.sessionId }.distinctUntilChanged(),
    ) { usage, sessionId -> usage to sessionId }
        .mapLatest { (usage, sessionId) -> topApps(usage, sessionId) }

    val state: StateFlow<NowUiState> = combine(
        combine(live, trace, source.settings.map { it.useFahrenheit }.distinctUntilChanged(), source.calibration, ::Now),
        combine(today, health, topApps, source.dischargeSessions(1).map { it.firstOrNull() }, ::Cards),
    ) { now, cards ->
        NowUiState(
            nowMs = now.live.nowMs,
            hero = now.live.hero,
            readouts = now.live.readouts,
            trace = now.trace,
            sinceUnplug = NowMapping.sinceUnplug(
                cards.onBattery,
                now.live.hero.monitoring,
                now.live.counterFullUah ?: cards.health?.estimate?.fullUah,
            ),
            today = cards.today,
            health = cards.health?.let(NowMapping::health),
            topApps = cards.topApps,
            calibrationNotice = NowMapping.notice(now.calibration),
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
            // Only the current on-battery window can be reset: a Reset racing a plug-in must not end the CHARGE session.
            NowEvent.ResetObservation -> if (state.value.sinceUnplug?.current == true) source.resetObservation()
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
        source.samplesSince(clock() - TraceRange.LIVE.spanMs).first().forEach { NowMapping.appendLive(buffer, it) }
        emit(Rows(buffer.lastOrNull()?.timestamp ?: clock(), buffer.toList()))
        source.realtime.collect { reading ->
            val sample = reading.sample ?: return@collect
            if (NowMapping.appendLive(buffer, sample)) emit(Rows(sample.timestamp, buffer.toList()))
        }
    }

    /** Stored rows for [selected]; the window ends at the time of each emission (a save, ≥ 30 s apart). */
    private fun historyRows(selected: TraceRange): Flow<Rows> = flow {
        emitAll(source.samplesSince(clock() - selected.spanMs).map { rows -> Rows(clock(), rows) })
    }

    private suspend fun topApps(usage: AppUsageSnapshot?, dischargeSessionId: String?): TopAppsState {
        if (usage == null) return TopAppsState.Empty
        val top = TopApps.of(usage, dischargeSessionId?.let { source.baseline(it) }) ?: return TopAppsState.Empty
        return TopAppsState.Ready(
            rows = top.rows.map { row ->
                TopApp(row.uid, row.packageName, AppLabel.of(row.uid, row.packageName, info(row.packageName)), row.powerMah, row.share)
            },
            basis = top.basis,
            capturedAtMs = top.capturedAt,
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
