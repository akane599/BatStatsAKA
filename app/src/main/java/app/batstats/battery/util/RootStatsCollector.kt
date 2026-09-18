package app.batstats.battery.util

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Curated, bounded reads execute inside su; app-UID File access is not a root read. */
object RootStatsCollector {
    private val probeLock = Mutex()
    private val readLock = Mutex()
    @Volatile private var cachedRoot: Boolean? = null
    @Volatile private var cachedAt = 0L
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors = _errors.asStateFlow()
    private val _collectedAt = MutableStateFlow<Map<String, Long>>(emptyMap())
    val collectedAt = _collectedAt.asStateFlow()

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
    fun invalidateRootCache() { cachedRoot = null; cachedAt = 0 }

    private suspend fun read(label: String, command: String): String? = readLock.withLock {
        val result = runInterruptible(Dispatchers.IO) { CommandOutput.run(listOf("su", "-c", command), 20_000, 256 * 1024) }
        _collectedAt.value = _collectedAt.value - label
        if (!result.successful || result.output.isBlank()) {
            _errors.value = _errors.value + (label to (result.error ?: "No supported readable kernel nodes"))
            null
        } else {
            _errors.value = _errors.value - label
            _collectedAt.value = _collectedAt.value + (label to System.currentTimeMillis())
            result.output
        }
    }
    suspend fun getKernelBatteryInfo(): KernelStats.Battery? {
        val raw = read("Battery", "cat /sys/class/power_supply/battery/uevent") ?: return null
        return KernelStats.battery(raw, System.currentTimeMillis()).also {
            if (it == null) _errors.value = _errors.value + ("Battery" to "Unrecognized battery uevent format")
        }
    }
    suspend fun getCpuInfo(): List<KernelStats.Cpu> = read("CPU", CPU_COMMAND)?.let(KernelStats::cpu).orEmpty()
    suspend fun getThermalZones(): List<KernelStats.Thermal> = read("Thermal", THERMAL_COMMAND)?.let(KernelStats::thermal).orEmpty()
    suspend fun getKernelWakelocks(): List<KernelStats.Wakelock> = read("Wake sources", WAKE_COMMAND)?.let(KernelStats::wakelocks).orEmpty()

    // Paths/field names are fixed. No caller-provided strings are interpolated into these scripts.
    private val CPU_COMMAND = """
        for p in /sys/devices/system/cpu/cpufreq/policy*; do
          [ -d "${'$'}p" ] || continue
          printf 'policy=%s\n' "${'$'}{p##*/}"
          for f in scaling_cur_freq scaling_min_freq scaling_max_freq scaling_governor; do
            [ -r "${'$'}p/${'$'}f" ] || continue
            printf '%s=' "${'$'}f"; cat "${'$'}p/${'$'}f"
          done
          [ ! -r "${'$'}p/stats/time_in_state" ] || sed 's/^/state=/' "${'$'}p/stats/time_in_state"
        done
    """.trimIndent()
    private val THERMAL_COMMAND = """
        for p in /sys/class/thermal/thermal_zone*; do
          [ -d "${'$'}p" ] || continue
          printf 'zone=%s\n' "${'$'}{p##*/}"
          for f in type temp trip_point_*_type trip_point_*_temp; do
            for n in "${'$'}p"/${'$'}f; do
              [ -r "${'$'}n" ] || continue
              printf '%s=' "${'$'}{n##*/}"; cat "${'$'}n"
            done
          done
        done
    """.trimIndent()
    private val WAKE_COMMAND = """
        for p in /sys/kernel/debug/wakeup_sources /d/wakeup_sources /proc/wakelocks; do
          if [ -r "${'$'}p" ]; then printf 'source=%s\n' "${'$'}p"; cat "${'$'}p"; exit ${'$'}?; fi
        done
        exit 1
    """.trimIndent()
}
