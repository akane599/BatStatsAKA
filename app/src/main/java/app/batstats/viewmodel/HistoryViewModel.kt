package app.batstats.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.batstats.battery.apps.AppUsageStatus
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.SessionEvidence
import app.batstats.battery.data.db.BatteryDatabase
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.DailySummary
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.measurement.DailySummaryAggregator
import java.time.ZoneId
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Days (daily totals and a stacked bar chart) or Sessions (one row per charge / discharge). */
enum class HistoryMode { DAYS, SESSIONS }

/** How many days the Days chart and list cover, ending today. */
enum class DayRange(val days: Int) { WEEK(7), TWO_WEEKS(14), MONTH(30) }

/** The Sessions chips; [type] is the DAO filter (null = every type). */
enum class SessionFilter(val type: SessionType?) { ALL(null), DISCHARGE(SessionType.DISCHARGE), CHARGE(SessionType.CHARGE) }

/** A discharge session whose per-app breakdown is missing for a reason worth a quiet hint. */
enum class AppUsageHint { NO_ACCESS, FAILED }

@Immutable
data class HistoryUiState(
    val mode: HistoryMode = HistoryMode.DAYS,
    val range: DayRange = DayRange.WEEK,
    val filter: SessionFilter = SessionFilter.ALL,
    /** The selected Days bar (local epoch day), or null for the range's daily average; always inside [DaysState.days]. */
    val selectedDay: Long? = null,
    /** The local day now, for "Today" / "Yesterday" labels. */
    val todayEpochDay: Long = 0,
    val monitoring: Boolean = false,
    val days: DaysState = DaysState(),
    val sessions: SessionsState = SessionsState(),
)

@Immutable
data class DaysState(
    /** One entry per day of the range, oldest first; days without a stored summary have no figures. */
    val days: List<DayEntry> = emptyList(),
    /** Per-day average over the recorded days (today left out while earlier days exist: it isn't over yet). */
    val average: DayFigures? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
) {
    val recorded: Boolean get() = days.any { it.figures != null }
}

@Immutable
data class DayEntry(val epochDay: Long, val figures: DayFigures?)

/** One day's totals: drain with the screen on and off (discharging only), charge taken in, deep sleep while on battery. */
@Immutable
data class DayFigures(
    val screenOnMs: Long,
    val screenOffMs: Long,
    val screenOnMah: Double,
    val screenOffMah: Double,
    val chargedMah: Double,
    val deepSleepPercent: Double?,
) {
    val usedMah: Double get() = screenOnMah + screenOffMah
}

@Immutable
data class SessionsState(
    val rows: List<SessionRow> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val hasMore: Boolean = false,
) {
    /** [rows] by the local day they started, newest day first (rows are newest first, so each day is contiguous). */
    val days: List<SessionDay> = rows.groupBy { it.startDay }.map { (day, dayRows) -> SessionDay(day, dayRows) }
}

/** The sessions that started on one local [epochDay], newest first: one panel in History › Sessions. */
@Immutable
data class SessionDay(val epochDay: Long, val rows: List<SessionRow>)

/**
 * One session: [endMs] is its end, the time now while it is being recorded, or its last saved reading when it was
 * left open (monitoring stopped or the process died). [chargeMah] is the counter's charge moved (≥ 0: taken in for a
 * charge, used for a discharge). [startDay] is the local epoch day it started on (the list groups by it).
 */
@Immutable
data class SessionRow(
    val sessionId: String,
    val type: SessionType,
    val startMs: Long,
    val endMs: Long,
    val startDay: Long,
    val recording: Boolean,
    val startLevel: Int?,
    val endLevel: Int?,
    val chargeMah: Double?,
    val appUsage: AppUsageHint?,
)

sealed interface HistoryEvent {
    data class SelectMode(val mode: HistoryMode) : HistoryEvent
    data class SelectRange(val range: DayRange) : HistoryEvent
    /** A bar or day row; null clears the selection. */
    data class SelectDay(val epochDay: Long?) : HistoryEvent
    data class SelectFilter(val filter: SessionFilter) : HistoryEvent
    data object LoadMore : HistoryEvent
    data object Retry : HistoryEvent
    /** From Now's Today card: Days, with today selected. */
    data object ShowToday : HistoryEvent
    /** Navigation: the screen opens SessionDetails. */
    data class OpenSession(val sessionId: String) : HistoryEvent
}

/** What History reads, as one seam: [DefaultHistoryRepository] on device, a fake in unit tests. */
interface HistoryRepository {
    /** The latest capture; each one re-evaluates the local day, so Days moves on after midnight without a timer. */
    val realtime: Flow<BatteryRepository.Realtime>
    val isMonitoring: Flow<Boolean>

    /** The observation being recorded right now (monitoring on and not stopped), else null. */
    val recordingObservation: Flow<String?>

    /** Stored daily summaries for [fromDay]..[toDay] (inclusive), oldest first. */
    fun days(fromDay: Long, toDay: Long): Flow<List<DailySummary>>

    /** The newest [limit] sessions of [type] (null = all), newest first. */
    fun sessions(type: SessionType?, limit: Int): Flow<List<ChargeSession>>
}

/** [HistoryRepository] over the app's repository and database. */
class DefaultHistoryRepository(
    private val repository: BatteryRepository,
    private val database: BatteryDatabase,
) : HistoryRepository {
    override val realtime = repository.realtimeFlow
    override val isMonitoring = repository.isMonitoringFlow
    override val recordingObservation = combine(repository.isMonitoringFlow, repository.observation) { monitoring, observation ->
        if (monitoring && !observation.stopped) observation.latest?.generation else null
    }
    override fun days(fromDay: Long, toDay: Long) = database.dailySummaryDao().between(fromDay, toDay)
    override fun sessions(type: SessionType?, limit: Int) = repository.sessionDao.filteredSessions(type, "", limit)
}

/**
 * History: Days (the last 7 / 14 / 30 days of `daily_summaries` as stacked screen-on / screen-off drain, a selected
 * day's figures, per-day rows) or Sessions (charge and discharge rows, filtered, 50 at a time). The mode, the range,
 * the chip and the selected day live in [savedState], so they survive rotation and process death.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val source: HistoryRepository,
    private val savedState: SavedStateHandle,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private data class Page(val limit: Int = PAGE_SIZE, val revision: Int = 0)

    private class Choices(val mode: HistoryMode, val range: DayRange, val filter: SessionFilter, val selectedDay: Long?)

    private val page = MutableStateFlow(Page())

    private val mode = savedState.getStateFlow(KEY_MODE, HistoryMode.DAYS.name).map { name -> enumOr(name, HistoryMode.DAYS) }
    private val range = savedState.getStateFlow(KEY_RANGE, DayRange.WEEK.name).map { name -> enumOr(name, DayRange.WEEK) }
    private val filter = savedState.getStateFlow(KEY_FILTER, SessionFilter.ALL.name).map { name -> enumOr(name, SessionFilter.ALL) }
    private val selectedDay = savedState.getStateFlow<Long?>(KEY_DAY, null)

    private val today: Flow<Long> = source.realtime
        .map { DailySummaryAggregator.epochDay(clock(), zone()) }
        .distinctUntilChanged()

    private val days: Flow<DaysState> = combine(range, today, page.map { it.revision }.distinctUntilChanged(), ::Triple)
        .flatMapLatest { (range, today, _) ->
            source.days(today - range.days + 1, today)
                .map { rows -> HistoryMapping.days(range, today, rows) }
                .catch { emit(DaysState(loading = false, failed = true)) }
        }

    private val sessions: Flow<SessionsState> = combine(filter, page, ::Pair)
        .flatMapLatest { (filter, page) ->
            combine(source.sessions(filter.type, page.limit + 1).distinctUntilChanged(), source.recordingObservation) { rows, recording ->
                SessionsState(
                    rows = rows.take(page.limit).map { HistoryMapping.session(it, recording, clock(), zone()) },
                    loading = false,
                    hasMore = rows.size > page.limit,
                )
            }.catch { emit(SessionsState(loading = false, failed = true)) }
        }

    val state: StateFlow<HistoryUiState> = combine(
        combine(mode, range, filter, selectedDay, ::Choices),
        today,
        source.isMonitoring,
        days,
        sessions,
    ) { choices, today, monitoring, days, sessions ->
        HistoryUiState(
            mode = choices.mode,
            range = choices.range,
            filter = choices.filter,
            selectedDay = choices.selectedDay?.takeIf { day -> days.days.any { it.epochDay == day } },
            todayEpochDay = today,
            monitoring = monitoring,
            days = days,
            sessions = sessions,
        )
    }
        .flowOn(computeDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HistoryUiState())

    /** State changes; [HistoryEvent.OpenSession] is the screen's. */
    fun onEvent(event: HistoryEvent) {
        when (event) {
            is HistoryEvent.SelectMode -> savedState[KEY_MODE] = event.mode.name
            is HistoryEvent.SelectRange -> savedState[KEY_RANGE] = event.range.name
            is HistoryEvent.SelectDay -> savedState[KEY_DAY] = event.epochDay
            is HistoryEvent.SelectFilter -> {
                savedState[KEY_FILTER] = event.filter.name
                page.update { it.copy(limit = PAGE_SIZE) }
            }
            HistoryEvent.LoadMore -> page.update { it.copy(limit = it.limit + PAGE_SIZE) }
            HistoryEvent.Retry -> page.update { it.copy(revision = it.revision + 1) }
            HistoryEvent.ShowToday -> {
                savedState[KEY_MODE] = HistoryMode.DAYS.name
                savedState[KEY_DAY] = DailySummaryAggregator.epochDay(clock(), zone())
            }
            is HistoryEvent.OpenSession -> Unit
        }
    }

    companion object {
        const val PAGE_SIZE = 50
        internal const val KEY_MODE = "history.mode"
        internal const val KEY_RANGE = "history.range"
        internal const val KEY_FILTER = "history.filter"
        internal const val KEY_DAY = "history.selectedDay"
        private const val STOP_TIMEOUT_MS = 5_000L

        /** A saved enum name back to its constant; a name this build doesn't know gives [default]. */
        private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default
    }
}

/** Pure row → UI mapping for [HistoryViewModel]. */
internal object HistoryMapping {
    private const val UAH_PER_MAH = 1_000.0
    private const val MIN_SLEEP_BASIS_MS = 60_000L

    /** One entry per day of [range] ending [today], with the stored figures where a summary exists. */
    fun days(range: DayRange, today: Long, rows: List<DailySummary>): DaysState {
        val byDay = rows.associateBy { it.epochDay }
        val entries = (today - range.days + 1..today).map { day -> DayEntry(day, byDay[day]?.let(::figures)) }
        val recorded = entries.filter { it.figures != null }
        // Today is still running: it would pull the average down, so it counts only when it is all there is.
        val averaged = recorded.filter { it.epochDay != today }.ifEmpty { recorded }
        return DaysState(days = entries, average = average(averaged.mapNotNull { day -> byDay[day.epochDay] }), loading = false)
    }

    fun figures(day: DailySummary): DayFigures = DayFigures(
        screenOnMs = day.screenOnMs.coerceAtLeast(0),
        screenOffMs = day.screenOffMs.coerceAtLeast(0),
        screenOnMah = day.screenOnDischargeUah.coerceAtLeast(0) / UAH_PER_MAH,
        screenOffMah = day.screenOffDischargeUah.coerceAtLeast(0) / UAH_PER_MAH,
        chargedMah = day.chargedUah.coerceAtLeast(0) / UAH_PER_MAH,
        deepSleepPercent = deepSleep(day.cpuSuspendMs, day.screenOnMs + day.screenOffMs),
    )

    /** The mean day: every figure averaged; deep sleep weighted by time on battery (suspend ÷ on-battery time). */
    fun average(days: List<DailySummary>): DayFigures? {
        if (days.isEmpty()) return null
        val count = days.size
        val slept = days.filter { it.cpuSuspendMs != null }
        return DayFigures(
            screenOnMs = days.sumOf { it.screenOnMs.coerceAtLeast(0) } / count,
            screenOffMs = days.sumOf { it.screenOffMs.coerceAtLeast(0) } / count,
            screenOnMah = days.sumOf { it.screenOnDischargeUah.coerceAtLeast(0) } / UAH_PER_MAH / count,
            screenOffMah = days.sumOf { it.screenOffDischargeUah.coerceAtLeast(0) } / UAH_PER_MAH / count,
            chargedMah = days.sumOf { it.chargedUah.coerceAtLeast(0) } / UAH_PER_MAH / count,
            deepSleepPercent = deepSleep(
                slept.takeIf { it.isNotEmpty() }?.sumOf { it.cpuSuspendMs ?: 0 },
                slept.sumOf { it.screenOnMs + it.screenOffMs },
            ),
        )
    }

    /** Suspend time as a share of time on battery; none from under a minute of it. */
    private fun deepSleep(suspendMs: Long?, onBatteryMs: Long): Double? {
        if (suspendMs == null || onBatteryMs < MIN_SLEEP_BASIS_MS) return null
        return (suspendMs.toDouble() / onBatteryMs * 100).coerceIn(0.0, 100.0)
    }

    fun session(session: ChargeSession, recordingObservation: String?, nowMs: Long, zone: ZoneId): SessionRow {
        val recording = SessionEvidence.isRecording(session, recordingObservation)
        val end = when {
            recording -> maxOf(nowMs, session.startTime)
            else -> session.endTime ?: SessionEvidence.lastEvidence(session)
        }
        return SessionRow(
            sessionId = session.sessionId,
            type = session.type,
            startMs = session.startTime,
            endMs = end,
            startDay = DailySummaryAggregator.epochDay(session.startTime, zone),
            recording = recording,
            startLevel = session.startLevel,
            endLevel = session.endLevel,
            // Same rule as SessionDetails: legacy rows and sessions the counter never covered show no charge.
            chargeMah = SessionEvidence.measuredChargeUah(session)?.let { abs(it) / UAH_PER_MAH },
            appUsage = if (session.type == SessionType.DISCHARGE) {
                when (session.appUsageStatus) {
                    AppUsageStatus.NO_ACCESS -> AppUsageHint.NO_ACCESS
                    AppUsageStatus.FAILED -> AppUsageHint.FAILED
                    else -> null
                }
            } else {
                null
            },
        )
    }
}
