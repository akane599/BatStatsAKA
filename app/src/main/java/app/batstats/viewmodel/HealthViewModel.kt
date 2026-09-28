package app.batstats.viewmodel

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.DesignCapacityReading
import app.batstats.battery.data.DesignCapacitySource
import app.batstats.battery.data.db.CapacityEstimateRow
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.data.uah
import app.batstats.battery.measurement.CapacityConfidence
import app.batstats.battery.measurement.HealthSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * What the Health screen reads, as one seam: [DefaultHealthRepository] on device, a fake in unit tests. The design
 * capacity is the app-wide [DesignCapacitySource] (the same one Now's Health card reads).
 */
interface HealthRepository {
    /** The Settings override, else sysfs `charge_full_design` read once through root (see [DesignCapacitySource]). */
    val design: Flow<DesignCapacityReading>

    /** The newest [limit] sessions of any type, newest first (the query Now's Health card reads). */
    fun recentSessions(limit: Int): Flow<List<ChargeSession>>

    /** The newest [limit] sessions with a stored capacity estimate, newest first (the trend's projection). */
    fun capacityEstimates(limit: Int): Flow<List<CapacityEstimateRow>>

    /** Whether Android reports a cycle count here (`EXTRA_CYCLE_COUNT`, API 34+). */
    val cyclesSupported: Boolean

    /** The sticky `ACTION_BATTERY_CHANGED` cycle count, or null when not reported. Main-safe. */
    suspend fun cycleCount(): Int?
}

/** [HealthRepository] over the app's repositories, the shared design capacity and the sticky battery broadcast. */
class DefaultHealthRepository(
    private val context: Context,
    private val repository: BatteryRepository,
    designCapacity: DesignCapacitySource,
) : HealthRepository {
    override val design = designCapacity.design
    override fun recentSessions(limit: Int) = repository.sessionDao.filteredSessions(null, "", limit)
    override fun capacityEstimates(limit: Int) = repository.sessionDao.capacityEstimates(limit)

    override val cyclesSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    override suspend fun cycleCount(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return withContext(Dispatchers.IO) {
            try {
                val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                sticky?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it >= 0 }
            } catch (e: RuntimeException) {
                null
            }
        }
    }
}

/**
 * Health: the combined capacity estimate against the design capacity (the shared [HealthSummary] rule, fed exactly
 * as Now's Health card is: the newest [HealthSummary.SESSIONS] sessions and the shared [DesignCapacitySource]), the
 * cycle count, and the newest [TREND_SESSIONS] stored per-session estimates for the trend.
 */
class HealthViewModel(
    private val source: HealthRepository,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val cycles: Flow<CycleCountState> = flow {
        emit(if (!source.cyclesSupported) CycleCountState.Unsupported else source.cycleCount()?.let(CycleCountState::Count) ?: CycleCountState.NotReported)
    }

    val state: StateFlow<HealthUiState> = combine(
        source.recentSessions(HealthSummary.SESSIONS),
        source.capacityEstimates(TREND_SESSIONS),
        source.design,
        cycles,
    ) { sessions, trend, design, cycleCount -> map(sessions, trend, design, cycleCount) }
        .distinctUntilChanged()
        .flowOn(computeDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HealthUiState())

    private fun map(
        sessions: List<ChargeSession>,
        trend: List<CapacityEstimateRow>,
        design: DesignCapacityReading,
        cycleCount: CycleCountState,
    ): HealthUiState {
        // Same list, same parse, same rule and design capacity as Now's card (NowMapping.healthSummary).
        val summary = HealthSummary.withDesign(
            sessions.mapNotNull { HealthSummary.storedEstimate(it.capacityEstimateMah, it.capacityConfidence, it.capacityBasis) },
            design.uah,
        )
        return HealthUiState(
            loaded = true,
            summary = summary?.let { HealthFigures(it.estimate.fullMah, it.estimate.confidence, it.healthPercent) },
            design = when (design) {
                is DesignCapacityReading.Known -> DesignCapacityState.Known(
                    mah = ((design.uah + UAH_ROUNDING) / UAH_PER_MAH).toInt(),
                    source = if (design.fromSettings) DesignSource.SETTINGS else DesignSource.BATTERY,
                )
                DesignCapacityReading.Checking -> DesignCapacityState.Checking
                DesignCapacityReading.Unknown -> DesignCapacityState.Unknown
            },
            cycles = cycleCount,
            estimates = trend.mapNotNull(::pointOf).sortedWith(compareBy({ it.timeMs }, { it.sessionId })),
        )
    }

    private fun pointOf(row: CapacityEstimateRow): CapacityPoint? {
        val estimate = HealthSummary.storedEstimate(row.capacityEstimateMah, row.capacityConfidence, row.capacityBasis) ?: return null
        return CapacityPoint(
            sessionId = row.sessionId,
            timeMs = row.endTime ?: row.lastSampleTime ?: row.startTime,
            type = row.type,
            startLevel = row.startLevel,
            endLevel = row.endLevel,
            capacityMah = estimate.fullMah,
            confidence = estimate.confidence,
        )
    }

    companion object {
        /** How many of the newest estimates the trend reads (months of history at a few sessions a day). */
        const val TREND_SESSIONS = 1_000

        private const val STOP_TIMEOUT_MS = 5_000L
        private const val UAH_PER_MAH = 1_000L
        private const val UAH_ROUNDING = 500L
    }
}

/** The Health screen: plain values; the UI formats them with the viewer's locale and string resources. */
data class HealthUiState(
    /** False until the first sessions query answers (the screen shows only its header until then). */
    val loaded: Boolean = false,
    /** The combined estimate (and health % when a design capacity is known); null before the first estimate. */
    val summary: HealthFigures? = null,
    val design: DesignCapacityState = DesignCapacityState.Checking,
    val cycles: CycleCountState = CycleCountState.Unsupported,
    /** Every stored per-session estimate in the trend window, oldest first. */
    val estimates: List<CapacityPoint> = emptyList(),
)

/** [HealthSummary] as shown: the same numbers as Now's Health card. */
data class HealthFigures(val capacityMah: Int, val confidence: CapacityConfidence, val healthPercent: Double?)

sealed interface DesignCapacityState {
    /** Settings is on auto and the one root sysfs read (app-wide, [DesignCapacitySource]) hasn't answered yet. */
    data object Checking : DesignCapacityState

    /** Settings is on auto and the battery doesn't report one (or there's no root): health % can't be shown. */
    data object Unknown : DesignCapacityState
    data class Known(val mah: Int, val source: DesignSource) : DesignCapacityState
}

enum class DesignSource {
    /** The Settings override. */
    SETTINGS,

    /** sysfs `charge_full_design`, read through root. */
    BATTERY,
}

sealed interface CycleCountState {
    /** Below API 34 Android has no cycle count: the UI hides it. */
    data object Unsupported : CycleCountState
    data object NotReported : CycleCountState
    data class Count(val cycles: Int) : CycleCountState
}

/** One session's stored full-capacity estimate, at the time the session ended (or its latest save while open). */
data class CapacityPoint(
    val sessionId: String,
    val timeMs: Long,
    val type: SessionType,
    val startLevel: Int?,
    val endLevel: Int?,
    val capacityMah: Int,
    val confidence: CapacityConfidence,
)
