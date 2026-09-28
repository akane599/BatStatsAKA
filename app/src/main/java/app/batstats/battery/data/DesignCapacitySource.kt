package app.batstats.battery.data

import app.batstats.battery.measurement.CapacityEstimator
import app.batstats.battery.util.RootStatsCollector
import app.batstats.settings.AppSettings
import app.batstats.settings.designCapacityOverrideMah
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext

/** The design capacity the health % is computed against, and where it comes from. */
sealed interface DesignCapacityReading {
    /** Design capacity is on auto and the one sysfs read hasn't answered yet. */
    data object Checking : DesignCapacityReading

    /** On auto, and the battery reports no plausible `charge_full_design` (or there's no root). */
    data object Unknown : DesignCapacityReading

    /** [uah] from the Settings override ([fromSettings]) or from sysfs `charge_full_design`. */
    data class Known(val uah: Long, val fromSettings: Boolean) : DesignCapacityReading
}

/** The design capacity in µAh, or null while it is checking or unknown. */
val DesignCapacityReading.uah: Long? get() = (this as? DesignCapacityReading.Known)?.uah

/**
 * One app-wide design capacity for Now's Health card and the Health screen, so both show the same health %: the
 * Settings override (mAh; 0 or an invalid value = auto) wins; on auto, sysfs `charge_full_design` is read once through
 * root ([readChargeFullDesignUah]) and cached. It is re-read only when the override switches back to auto, never per
 * screen or subscriber, so a root prompt can't repeat and a reopened screen doesn't flash "Checking". A value outside
 * [CapacityEstimator.PLAUSIBLE_FULL_UAH] (e.g. mAh in the µAh field) or a failed read is [DesignCapacityReading.Unknown].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesignCapacitySource(
    settings: Flow<AppSettings>,
    private val readChargeFullDesignUah: suspend () -> Long?,
    scope: CoroutineScope,
) {
    /** Starts with the first subscriber and then stays up in [scope], replaying the latest reading. */
    val design: Flow<DesignCapacityReading> = settings
        .map { it.designCapacityOverrideMah }
        .distinctUntilChanged()
        .transformLatest { overrideMah ->
            if (overrideMah > 0) {
                emit(DesignCapacityReading.Known(overrideMah * UAH_PER_MAH, fromSettings = true))
            } else {
                emit(DesignCapacityReading.Checking)
                emit(readSysfs())
            }
        }
        .distinctUntilChanged()
        .shareIn(scope, SharingStarted.Lazily, replay = 1)

    private suspend fun readSysfs(): DesignCapacityReading {
        val uah = try {
            readChargeFullDesignUah()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return uah?.takeIf { it in CapacityEstimator.PLAUSIBLE_FULL_UAH }
            ?.let { DesignCapacityReading.Known(it, fromSettings = false) }
            ?: DesignCapacityReading.Unknown
    }

    companion object {
        private const val UAH_PER_MAH = 1_000L

        /** sysfs `charge_full_design` in µAh through root; null without root or when the read fails. Main-safe. */
        suspend fun readRootChargeFullDesignUah(): Long? = withContext(Dispatchers.IO) {
            if (RootStatsCollector.isRootAvailable()) RootStatsCollector.getKernelBatteryInfo()?.chargeFullDesign else null
        }
    }
}
