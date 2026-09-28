package app.batstats.viewmodel

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.measurement.CapacityConfidence
import app.batstats.battery.measurement.CapacityEstimate
import app.batstats.battery.measurement.CapacityEstimator
import app.batstats.battery.measurement.HealthSummary
import app.batstats.battery.util.RootStatsCollector
import app.batstats.settings.AppSettings
import app.batstats.settings.designCapacityOverrideMah
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * What the Health screen reads, as one seam: [DefaultHealthRepository] on device, a fake in unit tests. The only
 * privileged read is sysfs `charge_full_design` through root, and only when Settings leaves design capacity on auto.
 */
interface HealthRepository {
    val settings: Flow<AppSettings>

    /** The newest [limit] sessions of any type, newest first (the query Now's Health card reads). */
    fun recentSessions(limit: Int): Flow<List<ChargeSession>>

    /** sysfs `charge_full_design` in µAh through root; null without root or when the read fails. Main-safe. */
    suspend fun chargeFullDesignUah(): Long?

    /** Whether Android reports a cycle count here (`EXTRA_CYCLE_COUNT`, API 34+). */
    val cyclesSupported: Boolean

    /** The sticky `ACTION_BATTERY_CHANGED` cycle count, or null when not reported. Main-safe. */
    suspend fun cycleCount(): Int?
}

/** [HealthRepository] over the app's repositories, the root sysfs reader and the sticky battery broadcast. */
class DefaultHealthRepository(
    private val context: Context,
    private val repository: BatteryRepository,
    settingsRepository: SettingsRepository<AppSettings>,
) : HealthRepository {
    override val settings = settingsRepository.flow
    override fun recentSessions(limit: Int) = repository.sessionDao.filteredSessions(null, "", limit)

    override suspend fun chargeFullDesignUah(): Long? = withContext(Dispatchers.IO) {
        try {
            if (RootStatsCollector.isRootAvailable()) RootStatsCollector.getKernelBatteryInfo()?.chargeFullDesign else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

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
 * as Now's Health card is: the newest [HealthSummary.SESSIONS] sessions and the Settings override, plus sysfs design
 * capacity when the override is auto), the cycle count, and every stored per-session estimate for the trend.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthViewModel(
    private val source: HealthRepository,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private sealed interface SysfsDesign {
        /** Settings has a design capacity: sysfs isn't read. */
        data object NotNeeded : SysfsDesign
        data object Reading : SysfsDesign
        data class Read(val uah: Long?) : SysfsDesign
    }

    private val designOverride: Flow<Int> = source.settings.map { it.designCapacityOverrideMah }.distinctUntilChanged()

    // Read once each time design capacity switches to auto, so a root prompt can't repeat on every settings change.
    private val sysfsDesign: Flow<SysfsDesign> = designOverride.map { it > 0 }.distinctUntilChanged().flatMapLatest { overridden ->
        if (overridden) {
            flowOf<SysfsDesign>(SysfsDesign.NotNeeded)
        } else {
            flow<SysfsDesign> {
                emit(SysfsDesign.Reading)
                emit(SysfsDesign.Read(source.chargeFullDesignUah()))
            }
        }
    }

    private val cycles: Flow<CycleCountState> = flow {
        emit(if (!source.cyclesSupported) CycleCountState.Unsupported else source.cycleCount()?.let(CycleCountState::Count) ?: CycleCountState.NotReported)
    }

    val state: StateFlow<HealthUiState> = combine(
        source.recentSessions(TREND_SESSIONS),
        designOverride,
        sysfsDesign,
        cycles,
    ) { sessions, overrideMah, sysfs, cycleCount -> map(sessions, overrideMah, sysfs, cycleCount) }
        .distinctUntilChanged()
        .flowOn(computeDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HealthUiState())

    private fun map(sessions: List<ChargeSession>, overrideMah: Int, sysfs: SysfsDesign, cycleCount: CycleCountState): HealthUiState {
        val sysfsUah = (sysfs as? SysfsDesign.Read)?.uah
        // Same list, same parse, same rule as Now's card: the query is ordered identically, so the first SESSIONS rows
        // are exactly what Now's recentSessions(SESSIONS) returns.
        val summary = HealthSummary.of(sessions.take(HealthSummary.SESSIONS).mapNotNull(::estimateOf), overrideMah, sysfsUah)
        val designUah = CapacityEstimator.designUah(overrideMah, sysfsUah)
        val design = when {
            designUah != null -> DesignCapacityState.Known(
                mah = ((designUah + UAH_ROUNDING) / UAH_PER_MAH).toInt(),
                source = if (overrideMah > 0) DesignSource.SETTINGS else DesignSource.BATTERY,
            )
            sysfs == SysfsDesign.Reading -> DesignCapacityState.Checking
            else -> DesignCapacityState.Unknown
        }
        return HealthUiState(
            loaded = true,
            summary = summary?.let { HealthFigures(it.estimate.fullMah, it.estimate.confidence, it.healthPercent) },
            design = design,
            cycles = cycleCount,
            estimates = sessions.mapNotNull(::pointOf).sortedWith(compareBy({ it.timeMs }, { it.sessionId })),
        )
    }

    private fun estimateOf(session: ChargeSession): CapacityEstimate? =
        HealthSummary.storedEstimate(session.capacityEstimateMah, session.capacityConfidence, session.capacityBasis)

    private fun pointOf(session: ChargeSession): CapacityPoint? {
        val estimate = estimateOf(session) ?: return null
        return CapacityPoint(
            sessionId = session.sessionId,
            timeMs = session.endTime ?: session.lastSampleTime ?: session.startTime,
            type = session.type,
            startLevel = session.startLevel,
            endLevel = session.endLevel,
            capacityMah = estimate.fullMah,
            confidence = estimate.confidence,
        )
    }

    companion object {
        /**
         * How many of the newest sessions the trend reads (months of history at a few sessions a day). The combined
         * figure still uses only the newest [HealthSummary.SESSIONS] of them.
         */
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
    /** Settings is on auto and the root sysfs read hasn't answered yet. */
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
