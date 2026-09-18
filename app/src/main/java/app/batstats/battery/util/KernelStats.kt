package app.batstats.battery.util

/** Linux ABI units only. Never infer a vendor scale from the size of a value. */
object KernelStats {
    data class Battery(
        val capturedAt: Long, val source: String,
        val technology: String?, val cycleCount: Int?, val chargeFullDesign: Long?, val chargeFull: Long?,
        val chargeNow: Long?, val currentNow: Long?, val voltageNow: Int?, val tempNow: Int?,
        val health: String?, val status: String?, val capacityLevel: String?, val timeToEmptyNow: Long?,
        val timeToFullNow: Long?, val batteryAge: Double?
    )
    data class Cpu(val cluster: Int, val currentFreq: Long?, val minFreq: Long?, val maxFreq: Long?,
        val governor: String?, val timeInState: Map<Long, Long>) // frequency kHz -> USER_HZ ticks (10ms)
    data class Thermal(val name: String, val type: String?, val tempMilliC: Int?, val tripPoints: List<Trip>)
    data class Trip(val type: String, val tempMilliC: Int)
    data class Wakelock(val name: String, val count: Long?, val wakeCount: Long?, val expireCount: Long?,
        val totalTimeMs: Long, val maxTimeMs: Long?, val source: String)

    fun battery(raw: String, at: Long): Battery? {
        val p = raw.lineSequence().filter { it.startsWith("POWER_SUPPLY_") && '=' in it }
            .associate { it.substringBefore('=').removePrefix("POWER_SUPPLY_") to it.substringAfter('=').trim() }
        if (p["TYPE"] != "Battery") return null
        fun text(key: String) = p[key]?.takeIf { it.isNotBlank() }
        fun number(key: String, range: LongRange) = p[key]?.toLongOrNull()?.takeIf { it in range }
        val design = number("CHARGE_FULL_DESIGN", 1_000..200_000_000L)
        val full = number("CHARGE_FULL", 1_000..200_000_000L)
        return Battery(at, "/sys/class/power_supply/battery/uevent", text("TECHNOLOGY"), number("CYCLE_COUNT", 0..Int.MAX_VALUE.toLong())?.toInt(),
            design, full, number("CHARGE_NOW", 0..200_000_000L), number("CURRENT_NOW", -100_000_000..100_000_000L),
            number("VOLTAGE_NOW", 1..30_000_000L)?.toInt(), number("TEMP", -500..1500L)?.toInt(),
            text("HEALTH"), text("STATUS"), text("CAPACITY_LEVEL"), number("TIME_TO_EMPTY_NOW", 0..604_800L),
            number("TIME_TO_FULL_NOW", 0..604_800L), if (design != null && full != null) full.toDouble() / design * 100 else null)
    }
    private fun blocks(raw: String, prefix: String): List<Map<String, List<String>>> {
        val result = mutableListOf<MutableMap<String, MutableList<String>>>()
        raw.lineSequence().forEach { line ->
            if (line.startsWith("$prefix=")) result += linkedMapOf()
            if ('=' in line) result.lastOrNull()?.getOrPut(line.substringBefore('=')) { mutableListOf() }?.add(line.substringAfter('=').trim())
        }
        return result
    }
    fun cpu(raw: String): List<Cpu> = blocks(raw, "policy").mapNotNull { b ->
        val id = b["policy"]?.firstOrNull()?.removePrefix("policy")?.toIntOrNull() ?: return@mapNotNull null
        fun frequency(key: String) = b[key]?.firstOrNull()?.toLongOrNull()?.takeIf { it in 1..100_000_000 }
        val times = b["state"].orEmpty().mapNotNull { row ->
            val p = row.split(Regex("\\s+")); val f = p.getOrNull(0)?.toLongOrNull(); val ticks = p.getOrNull(1)?.toLongOrNull()
            if (f != null && f > 0 && ticks != null && ticks >= 0) f to ticks else null
        }.toMap()
        Cpu(id, frequency("scaling_cur_freq"), frequency("scaling_min_freq"), frequency("scaling_max_freq"),
            b["scaling_governor"]?.firstOrNull()?.takeIf { it.isNotBlank() }, times)
    }
    fun thermal(raw: String): List<Thermal> = blocks(raw, "zone").mapNotNull { b ->
        val name = b["zone"]?.firstOrNull() ?: return@mapNotNull null
        fun temperature(key: String) = b[key]?.firstOrNull()?.toIntOrNull()?.takeIf { it in -273_150..1_000_000 }
        val trips = (0..63).mapNotNull { i ->
            val type = b["trip_point_${i}_type"]?.firstOrNull()?.takeIf { it.isNotBlank() }
            val temp = temperature("trip_point_${i}_temp")
            if (type != null && temp != null) Trip(type, temp) else null
        }
        Thermal(name, b["type"]?.firstOrNull()?.takeIf { it.isNotBlank() }, temperature("temp"), trips)
    }
    fun wakelocks(raw: String): List<Wakelock> {
        val lines = raw.lineSequence().filter { it.isNotBlank() }.toList()
        val source = lines.firstOrNull()?.removePrefix("source=") ?: return emptyList()
        val modern = source.endsWith("/wakeup_sources")
        if (!modern && source != "/proc/wakelocks") return emptyList()
        val header = lines.getOrNull(1)?.trim()?.split(Regex("\\s+")) ?: return emptyList()
        if (header.firstOrNull() != "name" || "total_time" !in header) return emptyList()
        return lines.drop(2).mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            fun counter(key: String) = parts.getOrNull(header.indexOf(key))?.toLongOrNull()?.takeIf { it >= 0 }
            fun time(key: String) = counter(key)?.let { if (modern) it else it / 1_000_000 }
            val total = time("total_time") ?: return@mapNotNull null
            Wakelock(parts.first().trim('"'), counter(if (modern) "event_count" else "count"),
                counter(if (modern) "wakeup_count" else "wake_count"), counter("expire_count"), total, time("max_time"), source)
        }.sortedByDescending { it.totalTimeMs }
    }
}
