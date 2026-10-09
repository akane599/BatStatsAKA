package com.akane.voltwise.battery.util

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Root probing (shared with [ShellRunner]'s access-mode detection) and the one surviving root sysfs read:
 * the battery's `charge_full_design`, for [com.akane.voltwise.battery.data.DesignCapacitySource]. Curated, bounded
 * reads execute inside su; app-UID File access is not a root read.
 */
object RootStatsCollector {
    private val probeLock = Mutex()
    @Volatile private var cachedRoot: Boolean? = null
    @Volatile private var cachedAt = 0L

    suspend fun isRootAvailable(): Boolean {
        cachedRoot?.let { if (SystemClock.elapsedRealtime() - cachedAt < 60_000) return it }
        return probeLock.withLock {
            cachedRoot?.let { if (SystemClock.elapsedRealtime() - cachedAt < 60_000) return@withLock it }
            val result = runInterruptible(Dispatchers.IO) { CommandOutput.run(listOf("su", "-c", "id"), 4_000, 4096) }
            val available = result.successful && Regex("(?:^|\\s)uid=0(?:\\D|$)").containsMatchIn(result.output)
            cachedRoot = available; cachedAt = SystemClock.elapsedRealtime()
            available
        }
    }
    fun invalidateRootCache() {
        cachedRoot = null; cachedAt = 0
    }

    /** sysfs `charge_full_design` in µAh; null on a read failure, an unreadable node, or a non-battery uevent. */
    suspend fun getChargeFullDesignUah(): Long? {
        val result = runInterruptible(Dispatchers.IO) {
            CommandOutput.run(listOf("su", "-c", "cat /sys/class/power_supply/battery/uevent"), 20_000, 256 * 1024)
        }
        if (!result.successful || result.output.isBlank()) return null
        return parseChargeFullDesignUah(result.output)
    }

    /** Linux ABI units only; never infer a vendor scale from the size of a value. */
    internal fun parseChargeFullDesignUah(raw: String): Long? {
        val fields = raw.lineSequence().filter { it.startsWith("POWER_SUPPLY_") && '=' in it }
            .associate { it.substringBefore('=').removePrefix("POWER_SUPPLY_") to it.substringAfter('=').trim() }
        if (fields["TYPE"] != "Battery") return null
        return fields["CHARGE_FULL_DESIGN"]?.toLongOrNull()?.takeIf { it in 1_000..200_000_000L }
    }
}
