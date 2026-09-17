package app.batstats.battery.util

/** Android checkin v9. Field offsets follow android16-release BatteryStats.java.
 * Missing/invalid fields remain null. Activity times are not energy measurements.
 */
object BatteryStatsParser {
    const val PER_USER_RANGE = 100_000
    const val FIRST_APPLICATION_UID = 10_000
    const val SYSTEM_UID = 1000
    fun appId(uid: Int): Int = uid % PER_USER_RANGE
    fun isSystemUid(uid: Int): Boolean = appId(uid) < FIRST_APPLICATION_UID
    @Suppress("UNUSED_PARAMETER")
    fun isUserApp(uid: Int, packages: List<String>): Boolean = uid >= 0 && !isSystemUid(uid)
    fun packagesFor(uid: Int, map: Map<Int, Collection<String>>): List<String> =
        (map[uid] ?: map[appId(uid)]).orEmpty().toList()
    fun displayNameFor(uid: Int, packages: List<String>): String = when {
        packages.size > 1 -> "Shared UID $uid"
        packages.size == 1 -> packages.single()
        appId(uid) == SYSTEM_UID -> "System UID $uid"
        else -> "UID $uid"
    }
    data class FullSnapshot(
        val capturedAt: Long = System.currentTimeMillis(),
        val statsSinceCharged: Boolean = true,
        val startedAt: Long? = null,
        val startCount: Long? = null,
        val batteryRealtimeMs: Long? = null,
        val batteryUptimeMs: Long? = null,
        val screenOnTimeMs: Long? = null,
        val screenOffTimeMs: Long? = null,
        val screenDozeTimeMs: Long? = null,
        val screenOffDischargePercent: Float? = null,
        val screenOnDischargePercent: Float? = null,
        val estimatedCapacityMah: Int? = null,
        val learnedMinCapacityUah: Long? = null,
        val learnedMaxCapacityUah: Long? = null,
        val reportedTags: Set<String> = emptySet(),
        val rejectedRecords: Int = 0,
        val source: String = "Android batterystats",
        val apps: List<AppPowerStats> = emptyList(),
        val componentEstimatesMah: Map<String, Double> = emptyMap(),
        val wakelocks: List<WakelockStats> = emptyList(),
        val kernelWakelocks: List<KernelWakelockStats> = emptyList(),
        val alarms: List<AlarmStats> = emptyList(),
        val jobs: List<JobStats> = emptyList(),
        val syncs: List<SyncStats> = emptyList(),
        val network: List<NetworkStats> = emptyList(),
        val sensors: List<SensorStats> = emptyList(),
        val signalStrength: List<SignalStrengthStats> = emptyList(),
        val wifiSignal: List<WifiSignalStats> = emptyList(),
        val bluetooth: BluetoothStats? = null,
        val doze: DozeStats? = null,
        val cpuFrequency: List<CpuFrequencyStats> = emptyList(),
        val processStats: List<ProcessStats> = emptyList()
    ) {
        val hasValidWindow: Boolean get() = startedAt != null && startCount != null &&
            batteryRealtimeMs != null && batteryUptimeMs != null && batteryUptimeMs <= batteryRealtimeMs
    }
    data class AppPowerStats(
        val uid: Int,
        val packageName: String,
        val powerMah: Double,
        val packages: List<String> = emptyList(),
        val cpuTimeMs: Long? = null,
        val cpuPowerMah: Double? = null,
        val wakeLockTimeMs: Long? = null,
        val wakeLockPowerMah: Double? = null,
        val mobilePowerMah: Double? = null,
        val wifiPowerMah: Double? = null,
        val gpsPowerMah: Double? = null,
        val sensorPowerMah: Double? = null,
        val cameraPowerMah: Double? = null,
        val flashlightPowerMah: Double? = null,
        val audioPowerMah: Double? = null,
        val videoPowerMah: Double? = null,
        val bluetoothPowerMah: Double? = null,
        val screenPowerMah: Double? = null,
        val proportionalSmearMah: Double? = null,
        val foregroundTimeMs: Long? = null,
        val foregroundServiceTimeMs: Long? = null,
        val backgroundTimeMs: Long? = null,
        val cachedTimeMs: Long? = null,
        val topTimeMs: Long? = null,
        val mobileRxBytes: Long? = null,
        val mobileTxBytes: Long? = null,
        val wifiRxBytes: Long? = null,
        val wifiTxBytes: Long? = null,
        val mobileRxPackets: Long? = null,
        val mobileTxPackets: Long? = null,
        val wifiRxPackets: Long? = null,
        val wifiTxPackets: Long? = null,
        val gpsTimeMs: Long? = null,
        val sensorTimeMs: Long? = null,
        val cameraTimeMs: Long? = null,
        val flashlightTimeMs: Long? = null,
        val audioTimeMs: Long? = null,
        val videoTimeMs: Long? = null,
        val bluetoothScanTimeMs: Long? = null,
        val bluetoothUnoptimizedScanTimeMs: Long? = null
    )

    data class WakelockStats(
        val uid: Int,
        val packageName: String,
        val packages: List<String> = emptyList(),
        val tag: String,
        val type: WakelockType,
        val count: Int,
        val totalTimeMs: Long,
        val maxTimeMs: Long? = null,
        val backgroundTimeMs: Long? = null,
        val backgroundCount: Int? = null
    )

    enum class WakelockType { PARTIAL, FULL, WINDOW, DRAW }

    data class KernelWakelockStats(
        val name: String,
        val count: Int,
        val totalTimeMs: Long,
        val activeCount: Int? = null,
        val maxTimeMs: Long? = null,
        val lastChangeMs: Long? = null,
        val preventSuspendTimeMs: Long? = null
    )

    data class AlarmStats(
        val uid: Int,
        val packageName: String,
        val packages: List<String> = emptyList(),
        val tag: String,
        val count: Int,
        val wakeups: Int,
        val totalTimeMs: Long?,
        val backgroundCount: Int? = null,
        val backgroundTimeMs: Long? = null
    )

    data class JobStats(
        val uid: Int,
        val packageName: String,
        val packages: List<String> = emptyList(),
        val jobName: String,
        val count: Int,
        val totalTimeMs: Long,
        val backgroundCount: Int? = null,
        val backgroundTimeMs: Long? = null
    )

    data class SyncStats(
        val uid: Int,
        val packageName: String,
        val packages: List<String> = emptyList(),
        val authority: String,
        val count: Int,
        val totalTimeMs: Long,
        val backgroundCount: Int? = null,
        val backgroundTimeMs: Long? = null
    )

    data class NetworkStats(
        val uid: Int,
        val packageName: String,
        val packages: List<String> = emptyList(),
        val mobileRxBytes: Long,
        val mobileTxBytes: Long,
        val wifiRxBytes: Long,
        val wifiTxBytes: Long,
        val btRxBytes: Long? = null,
        val btTxBytes: Long? = null,
        val mobileActiveTimeMs: Long? = null,
        val mobileActiveCount: Int? = null
    )

    data class SensorStats(
        val uid: Int,
        val packageName: String,
        val packages: List<String> = emptyList(),
        val sensorHandle: Int,
        val sensorName: String,
        val count: Int,
        val totalTimeMs: Long,
        val backgroundTimeMs: Long? = null,
        val backgroundCount: Int? = null
    )

    data class SignalStrengthStats(
        val level: Int, // 0 (none) to 4 (great)
        val durationMs: Long,
        val percentOfTotal: Float
    )

    data class WifiSignalStats(
        val level: Int, // 0 (none) to 4 (great)
        val durationMs: Long,
        val percentOfTotal: Float
    )

    data class BluetoothStats(
        val idleTimeMs: Long,
        val rxTimeMs: Long,
        val txTimeMs: Long,
        val powerMah: Double,
        val scanTimeMs: Long? = null
    )

    data class DozeStats(
        val idleModeTimeMs: Long,
        val idleModeCount: Int,
        val deepIdleTimeMs: Long,
        val deepIdleCount: Int,
        val lightIdleTimeMs: Long,
        val lightIdleCount: Int,
        val maintenanceTimeMs: Long? = null,
        val maintenanceCount: Int? = null
    )

    data class CpuFrequencyStats(
        val cluster: Int,
        val frequency: Long,
        val timeMs: Long,
        val percentOfTotal: Float
    )

    data class ProcessStats(
        val uid: Int,
        val packageName: String,
        val packages: List<String> = emptyList(),
        val processName: String,
        val userTimeMs: Long,
        val systemTimeMs: Long,
        val foregroundTimeMs: Long,
        val starts: Int
    )


    private fun List<String>.long(i: Int) = getOrNull(i)?.toLongOrNull()?.takeIf { it >= 0 }
    private fun List<String>.int(i: Int) = long(i)?.takeIf { it <= Int.MAX_VALUE }?.toInt()
    private fun List<String>.number(i: Int) = getOrNull(i)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    private fun sum(a: Long?, b: Long?): Long? = if (a == null || b == null || a > Long.MAX_VALUE - b) null else a + b

    fun parseCheckin(raw: String): FullSnapshot {
        val mappings = mutableMapOf<Int, LinkedHashSet<String>>()
        var rejected = 0
        // Ignore included history without retaining it; mappings may follow usage records.
        fun records() = raw.lineSequence().filter { it.startsWith("9,") && !it.startsWith("9,h,") }
            .map(::splitCheckinLine).filter { it.size >= 4 }
        records().filter { it[2] == "i" && it[3] == "uid" }.forEach { p ->
            val uid = p.int(4)
            if (uid != null && !p.getOrNull(5).isNullOrBlank()) mappings.getOrPut(uid) { linkedSetOf() }.add(p[5])
        }
        val rows = records().filter { it[2] == "l" }.toList()
        val perUid = rows.groupBy { it.int(1) }
        val tags = mutableSetOf<String>()
        val apps = linkedMapOf<Int, AppPowerStats>()
        val components = linkedMapOf<String, Double>()
        val locks = mutableListOf<WakelockStats>()
        val kernel = mutableListOf<KernelWakelockStats>()
        val alarms = mutableListOf<AlarmStats>()
        val jobs = mutableListOf<JobStats>()
        val syncs = mutableListOf<SyncStats>()
        val network = mutableListOf<NetworkStats>()
        val sensors = mutableListOf<SensorStats>()
        val processes = mutableListOf<ProcessStats>()
        val signals = mutableListOf<SignalStrengthStats>()
        val wifi = mutableListOf<WifiSignalStats>()
        var snapshot = FullSnapshot()
        var bluetooth: BluetoothStats? = null
        var doze: DozeStats? = null
        var frequencies = emptyList<Long>()
        rows.forEach { p ->
            val uid = p.int(1) ?: return@forEach
            val pkgs = packagesFor(uid, mappings)
            val label = displayNameFor(uid, pkgs)
            tags += p[3]
            when (p[3]) {
                "bt" -> if (uid == 0) snapshot = snapshot.copy(startCount = p.long(4), batteryRealtimeMs = p.long(5),
                    batteryUptimeMs = p.long(6), startedAt = p.long(9)?.takeIf { it > 0 }, screenOffTimeMs = p.long(10),
                    learnedMinCapacityUah = p.long(13)?.takeIf { it > 0 }, learnedMaxCapacityUah = p.long(14)?.takeIf { it > 0 },
                    screenDozeTimeMs = p.long(15))
                "m" -> if (uid == 0) {
                    snapshot = snapshot.copy(screenOnTimeMs = p.long(4))
                    val deep = p.long(13); val light = p.long(19); val dc = p.int(14); val lc = p.int(20)
                    if (deep != null && light != null && dc != null && lc != null && sum(deep, light) != null && dc.toLong() + lc <= Int.MAX_VALUE)
                        doze = DozeStats(deep + light, dc + lc, deep, dc, light, lc)
                }
                "dc" -> if (uid == 0) snapshot = snapshot.copy(screenOnDischargePercent = p.number(6)?.toFloat(), screenOffDischargePercent = p.number(7)?.toFloat())
                "pws" -> if (uid == 0) snapshot = snapshot.copy(estimatedCapacityMah = p.number(4)?.takeIf { it > 0 && it <= 200_000 }?.toInt())
                "pwi" -> {
                    val energy = p.number(5)
                    if (energy == null) { rejected++; return@forEach }
                    if (p.getOrNull(4) == "uid") {
                        if (uid in apps) { rejected++; return@forEach }
                        apps[uid] = AppPowerStats(uid, label, energy, pkgs, screenPowerMah = p.number(7), proportionalSmearMah = p.number(8))
                    } else if (uid == 0 && p.size > 4) components.putIfAbsent(p[4], energy)
                }
                "wl" -> {
                    val name = p.getOrNull(4) ?: return@forEach
                    val bg = (6 until p.size step 6).firstOrNull { p[it] == "bp" }
                    for (i in 6 until p.size step 6) {
                        val type = when (p[i]) { "f" -> WakelockType.FULL; "p" -> WakelockType.PARTIAL; "w" -> WakelockType.WINDOW; else -> continue }
                        val time = p.long(i - 1); val count = p.int(i + 1)
                        if (time == null || count == null) { rejected++; continue }
                        locks += WakelockStats(uid, label, pkgs, name, type, count, time, p.long(i + 3),
                            if (type == WakelockType.PARTIAL && bg != null) p.long(bg - 1) else null,
                            if (type == WakelockType.PARTIAL && bg != null) p.int(bg + 1) else null)
                    }
                }
                "kwl" -> {
                    val name = p.getOrNull(4); val time = p.long(5); val count = p.int(6)
                    if (name != null && time != null && count != null) kernel += KernelWakelockStats(name, count, time, maxTimeMs = p.long(8)) else rejected++
                }
                "wua" -> {
                    val name = p.getOrNull(4); val count = p.int(5)
                    if (name != null && count != null) alarms += AlarmStats(uid, label, pkgs, name, count, count, null) else rejected++
                }
                "jb", "sy" -> {
                    val name = p.getOrNull(4); val time = p.long(5); val count = p.int(6)
                    if (name == null || time == null || count == null) { rejected++; return@forEach }
                    if (p[3] == "jb") jobs += JobStats(uid, label, pkgs, name, count, time, p.int(8), p.long(7))
                    else syncs += SyncStats(uid, label, pkgs, name, count, time, p.int(8), p.long(7))
                }
                "nt" -> {
                    val bytes = (4..7).map { p.long(it) }
                    if (bytes.any { it == null }) { rejected++; return@forEach }
                    network += NetworkStats(uid, label, pkgs, bytes[0]!!, bytes[1]!!, bytes[2]!!, bytes[3]!!,
                        p.long(14), p.long(15), p.long(12)?.div(1000), p.int(13))
                }
                "sr" -> {
                    val handle = p.getOrNull(4)?.toIntOrNull(); val time = p.long(5); val count = p.int(6)
                    if (handle != null && time != null && count != null) sensors += SensorStats(uid, label, pkgs, handle,
                        if (handle == -10000) "GPS" else "Sensor #$handle", count, time, p.long(9), p.int(7)) else rejected++
                }
                "pr" -> {
                    val name = p.getOrNull(4); val user = p.long(5); val system = p.long(6); val foreground = p.long(7); val starts = p.int(8)
                    if (name != null && user != null && system != null && foreground != null && starts != null)
                        processes += ProcessStats(uid, label, pkgs, name, user, system, foreground, starts) else rejected++
                }
                "sgt", "wsgt" -> if (uid == 0) {
                    val times = (4..8).map { p.long(it) }
                    if (times.any { it == null }) { rejected++; return@forEach }
                    val total = times.sumOf { it!!.toDouble() }
                    times.forEachIndexed { level, time ->
                        val fraction = if (total > 0) (time!! / total).toFloat() else 0f
                        if (p[3] == "sgt") signals += SignalStrengthStats(level, time!!, fraction)
                        else wifi += WifiSignalStats(level, time!!, fraction)
                    }
                }
                "gble" -> if (uid == 0) {
                    val idle = p.long(4); val rx = p.long(5); val power = p.number(6)
                    val tx = p.drop(8).map { it.toLongOrNull()?.takeIf { n -> n >= 0 } }
                    val txTotal = tx.fold(0L as Long?) { a, b -> sum(a, b) }
                    if (idle != null && rx != null && power != null && tx.isNotEmpty() && txTotal != null)
                        bluetooth = BluetoothStats(idle, rx, txTotal, power) else rejected++
                }
                "gcf" -> if (uid == 0) frequencies = p.drop(4).mapNotNull { it.toLongOrNull()?.takeIf { f -> f > 0 } }
            }
        }
        // Checkin gives per-UID activity, but only total/screen/proportional UID charge estimates.
        val enriched = apps.map { (uid, app) ->
            val uidRows = perUid[uid].orEmpty()
            fun record(tag: String) = uidRows.firstOrNull { it[3] == tag }
            fun duration(tag: String) = record(tag)?.long(4)
            val cpu = record("cpu"); val state = record("st"); val net = record("nt"); val scan = record("blem")
            val sensorTimes = sensors.filter { it.uid == uid && it.sensorHandle != -10000 }
            app.copy(cpuTimeMs = cpu?.let { sum(it.long(4), it.long(5)) }, wakeLockTimeMs = duration("awl"),
                foregroundTimeMs = duration("fg"), foregroundServiceTimeMs = duration("fgs"),
                topTimeMs = state?.long(4), backgroundTimeMs = state?.long(7), cachedTimeMs = state?.long(10),
                mobileRxBytes = net?.long(4), mobileTxBytes = net?.long(5), wifiRxBytes = net?.long(6), wifiTxBytes = net?.long(7),
                mobileRxPackets = net?.long(8), mobileTxPackets = net?.long(9), wifiRxPackets = net?.long(10), wifiTxPackets = net?.long(11),
                gpsTimeMs = sensors.firstOrNull { it.uid == uid && it.sensorHandle == -10000 }?.totalTimeMs,
                sensorTimeMs = sensorTimes.takeIf { it.isNotEmpty() }?.fold(0L as Long?) { a, b -> sum(a, b.totalTimeMs) },
                cameraTimeMs = duration("cam"), flashlightTimeMs = duration("fla"), audioTimeMs = duration("aud"), videoTimeMs = duration("vid"),
                bluetoothScanTimeMs = scan?.long(4), bluetoothUnoptimizedScanTimeMs = scan?.long(11))
        }
        val frequencyTimes = Array<Long?>(frequencies.size) { 0L }
        var frequencyRows = 0
        rows.filter { it[3] == "ctf" && it.getOrNull(4) == "A" }.distinctBy { it[1] }.forEach { p ->
            if (p.int(5) == frequencies.size && frequencies.isNotEmpty() && p.size >= 6 + frequencies.size) {
                frequencyRows++
                frequencyTimes.indices.forEach { i -> frequencyTimes[i] = sum(frequencyTimes[i], p.long(6 + i)) }
            }
        }
        val totalFrequency = frequencyTimes.sumOf { (it ?: 0).toDouble() }
        val cpu = if (frequencyRows == 0) emptyList() else frequencies.indices.mapNotNull { i ->
            frequencyTimes[i]?.let { CpuFrequencyStats(-1, frequencies[i], it, if (totalFrequency > 0) (it / totalFrequency).toFloat() else 0f) }
        }
        return snapshot.copy(apps = enriched.sortedByDescending { it.powerMah }, componentEstimatesMah = components,
            reportedTags = tags, rejectedRecords = rejected, wakelocks = locks.sortedByDescending { it.totalTimeMs },
            kernelWakelocks = kernel.sortedByDescending { it.totalTimeMs }, alarms = alarms.sortedByDescending { it.count },
            jobs = jobs.sortedByDescending { it.totalTimeMs }, syncs = syncs.sortedByDescending { it.totalTimeMs },
            network = network.distinctBy { it.uid }.sortedByDescending { it.mobileRxBytes.toDouble() + it.mobileTxBytes + it.wifiRxBytes + it.wifiTxBytes },
            sensors = sensors.sortedByDescending { it.totalTimeMs }, signalStrength = signals, wifiSignal = wifi,
            bluetooth = bluetooth, doze = doze, cpuFrequency = cpu, processStats = processes.sortedByDescending { it.userTimeMs.toDouble() + it.systemTimeMs })
    }

    internal fun splitCheckinLine(line: String): List<String> {
        if ('"' !in line) return line.split(',')
        val fields = ArrayList<String>(16)
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { fields.add(field.toString()); field.setLength(0) }
                else -> field.append(c)
            }
            i++
        }
        if (quoted) return emptyList()
        fields.add(field.toString())
        return fields
    }

    data class DeviceIdleInfo(
        val currentState: String, val lightState: String, val deepEnabled: Boolean?, val lightEnabled: Boolean?,
        val screenOnTime: Long?, val screenOffTime: Long?, val whitelistedApps: List<String>, val tempWhitelistedApps: List<String>
    )
    private fun token(raw: String, name: String): String? = Regex("(?:^|\\s)${Regex.escape(name)}=([^\\s]+)", RegexOption.MULTILINE)
        .find(raw)?.groupValues?.get(1)
    private fun bool(raw: String, name: String) = token(raw, name)?.toBooleanStrictOrNull()
    fun parseDeviceIdle(raw: String): DeviceIdleInfo {
        val allow = mutableListOf<String>(); val temporary = mutableListOf<String>()
        var section = 0
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                (trimmed.startsWith("Whitelist") || trimmed.startsWith("Power save whitelist")) && trimmed.endsWith("apps:") -> section = 1
                trimmed.startsWith("Temp whitelist") || trimmed.startsWith("Temp power save whitelist") -> section = 2
                trimmed.isEmpty() || trimmed.endsWith(":") || '=' in trimmed -> section = 0
                section == 1 -> allow += trimmed
                section == 2 -> temporary += trimmed
            }
        }
        return DeviceIdleInfo(token(raw, "mState") ?: "UNKNOWN", token(raw, "mLightState") ?: "UNKNOWN",
            bool(raw, "mDeepEnabled"), bool(raw, "mLightEnabled"), null, null, allow.distinct(), temporary.distinct())
    }
    data class PowerManagerInfo(
        val screenBrightness: Double?, val isScreenOn: Boolean?, val holdingWakeLocks: List<String>,
        val suspendBlockers: List<String>, val batteryLevel: Int?, val batteryStatus: String,
        val lowPowerMode: Boolean?, val deviceIdleMode: String
    )
    fun parsePowerManager(raw: String): PowerManagerInfo {
        val locks = mutableListOf<String>(); val blockers = mutableListOf<String>()
        var section = 0
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Wake Locks:") -> section = 1
                trimmed.startsWith("Suspend Blockers:") -> section = 2
                trimmed.isEmpty() || !line.startsWith("  ") -> section = 0
                section == 1 -> locks += trimmed
                section == 2 -> blockers += trimmed
            }
        }
        val interactive = when (token(raw, "mWakefulness")?.lowercase()) { "awake", "dreaming" -> true; "asleep", "dozing" -> false; else -> null }
        val display = Regex("Display Power: state=(ON|OFF|DOZE|DOZE_SUSPEND)\\b").find(raw)?.groupValues?.get(1)
        val state = token(raw, "mBatteryStatus") ?: when (bool(raw, "mIsPowered")) { true -> "Plugged in"; false -> "Unplugged"; null -> "UNKNOWN" }
        return PowerManagerInfo(token(raw, "mScreenBrightnessSetting")?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 },
            interactive ?: display?.let { it == "ON" }, locks, blockers,
            token(raw, "mBatteryLevel")?.toIntOrNull()?.takeIf { it in 0..100 }, state,
            bool(raw, "mLowPowerModeEnabled") ?: bool(raw, "mBatterySaverEnabled"), token(raw, "mDeviceIdleMode") ?: "UNKNOWN")
    }
}
