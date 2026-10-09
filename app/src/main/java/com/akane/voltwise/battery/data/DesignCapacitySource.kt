package com.akane.voltwise.battery.data

import com.akane.voltwise.battery.measurement.CapacityEstimator
import com.akane.voltwise.battery.util.RootStatsCollector
import com.akane.voltwise.settings.AppSettings
import com.akane.voltwise.settings.designCapacityOverrideMah
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
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
 * Settings override (mAh; 0 or an invalid value = auto) wins; on auto, sysfs `charge_full_design` is read through
 * root ([readChargeFullDesignUah]) and cached. Only [design] (Health, an explicit path) starts that read, once; it is
 * re-read when the override switches back to auto or after [recheck], never per screen or subscriber, so a root
 * prompt can't repeat and a reopened screen doesn't flash "Checking". [known] (Now) never reads. A value outside
 * [CapacityEstimator.PLAUSIBLE_FULL_UAH] (e.g. mAh in the µAh field) or a failed read is [DesignCapacityReading.Unknown].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesignCapacitySource(
    settings: Flow<AppSettings>,
    private val readChargeFullDesignUah: suspend () -> Long?,
    scope: CoroutineScope,
) {
    private val overrideMah = settings.map { it.designCapacityOverrideMah }.distinctUntilChanged()

    /** The last sysfs result; null before the first read of this process has answered. */
    private val lastRead = MutableStateFlow<DesignCapacityReading?>(null)

    /** Bumped by [recheck]: [design] reads sysfs again, on auto. */
    private val rereads = MutableStateFlow(0)

    /** Starts with the first subscriber and then stays up in [scope], replaying the latest reading. */
    val design: Flow<DesignCapacityReading> = combine(overrideMah, rereads, ::Pair)
        .transformLatest { (mah, _) ->
            if (mah > 0) {
                emit(DesignCapacityReading.Known(mah * UAH_PER_MAH, fromSettings = true))
            } else {
                emit(DesignCapacityReading.Checking)
                emit(readSysfs().also { lastRead.value = it })
            }
        }
        .distinctUntilChanged()
        .shareIn(scope, SharingStarted.Lazily, replay = 1)

    /**
     * The design capacity only when it is already known — the override, or a root read [design] has made — and
     * [DesignCapacityReading.Unknown] otherwise. Never starts privileged work: Now reads this.
     */
    val known: Flow<DesignCapacityReading> = combine(overrideMah, lastRead) { mah, read ->
        if (mah > 0) DesignCapacityReading.Known(mah * UAH_PER_MAH, fromSettings = true) else read ?: DesignCapacityReading.Unknown
    }.distinctUntilChanged()

    /**
     * The user asked to check access again (Status "Check again", after the root cache was reset): an Unknown sysfs
     * reading is read again, since a slow root grant can answer after the first read timed out. A known one is kept.
     */
    fun recheck() {
        if (lastRead.value == DesignCapacityReading.Unknown) rereads.update { it + 1 }
    }

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
            if (RootStatsCollector.isRootAvailable()) RootStatsCollector.getChargeFullDesignUah() else null
        }
    }
}
