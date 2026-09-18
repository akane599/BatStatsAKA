package app.batstats.battery.data

import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.measurement.BatteryReading
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Imported records are historical evidence, never a resumed live observation. */
object HistoryPolicy {
    private val canonicalJson = Json { encodeDefaults = true }
    private const val MAX_TIMESTAMP = 253402300799999L // end of year9999, milliseconds since Unix epoch
    fun originalId(value: String) = value.removePrefix("import:")
    private fun identity(value: String?): String? = value?.let {
        require(it.isNotBlank() && originalId(it).length <= 240 && it.none { c -> c.isISOControl() }) { "Invalid history identity" }
        "import:${originalId(it)}"
    }
    private fun source(value: String) = "import:${value.removePrefix("import:").take(128)}"
    private fun text(value: String?): String? = value?.also { require(it.length <= 512 && it.trimStart().firstOrNull() !in listOf('=', '+', '-', '@') && '\u0000' !in it) { "Invalid history text" } }
    private fun epoch(value: Long) { require(value in 0..MAX_TIMESTAMP) { "Invalid timestamp; expected Unix milliseconds" } }
    private fun charge(value: Long?): Long? {
        if (value == Long.MIN_VALUE || value == Int.MIN_VALUE.toLong()) return null
        return value?.let(BatteryReading::chargeUah).also { require(value == null || it != null) { "Invalid charge counter; expected µAh" } }
    }
    private fun current(value: Long?): Long? {
        if (value == Long.MIN_VALUE || value == Int.MIN_VALUE.toLong()) return null
        return value?.let(BatteryReading::currentUa).also { require(value == null || it != null) { "Invalid current; expected µA" } }
    }
    fun sample(input: BatterySample): BatterySample {
        epoch(input.timestamp)
        require(input.levelPercent == null || input.levelPercent in 0..100) { "Invalid battery percentage" }
        require(input.status in 1..5 && (input.plugged == null || input.plugged in 0..15)) { "Invalid power state" }
        require(input.elapsedMs == null || input.elapsedMs >= 0) { "Invalid elapsed time" }
        require(input.uptimeMs == null || input.elapsedMs != null && input.uptimeMs in 0..input.elapsedMs) { "Invalid uptime" }
        val voltage = input.voltageMv?.takeUnless { it == 0 || it == Int.MIN_VALUE }
        require(voltage == null || BatteryReading.voltageMv(voltage) != null) { "Invalid voltage; expected mV" }
        val temperature = input.temperatureDeciC?.takeUnless { it == Int.MIN_VALUE }
        require(temperature == null || BatteryReading.temperatureDeciC(temperature) != null) { "Invalid temperature; expected tenths Celsius" }
        require(input.health == null || input.health in 1..7) { "Invalid health category" }
        require(input.cycleCount == null || input.cycleCount >= 0) { "Invalid cycle count" }
        require(input.energyNwh == null || BatteryReading.energyNwh(input.energyNwh) != null) { "Invalid energy; expected nWh" }
        require(input.etaMs == null || input.etaMs in 1..604_800_000L) { "Invalid remaining-time estimate" }
        val normalized = input.copy(id = 0, source = source(text(input.source)!!),
            sessionId = identity(input.sessionId), observationId = identity(input.observationId),
            currentNowUa = current(input.currentNowUa), currentAverageUa = current(input.currentAverageUa),
            chargeCounterUah = charge(input.chargeCounterUah), voltageMv = voltage, temperatureDeciC = temperature,
            etaBasis = text(input.etaBasis), boundaryReason = text(input.boundaryReason))
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalJson.encodeToString(BatterySample.serializer(), normalized).toByteArray())
        // Local AUTOINCREMENT IDs are positive. A collision is checked against complete row content before insert.
        val id = ByteBuffer.wrap(digest).long or Long.MIN_VALUE
        return normalized.copy(id = if (id == -1L) -2L else id)
    }
    fun sameSample(first: BatterySample, second: BatterySample): Boolean = sample(first) == sample(second)
    fun session(input: ChargeSession): ChargeSession {
        epoch(input.startTime)
        input.endTime?.let(::epoch); input.lastSampleTime?.let(::epoch)
        val end = input.endTime ?: input.lastSampleTime ?: input.startTime
        require(end >= input.startTime) { "Session ends before it starts" }
        require(input.startLevel == null || input.startLevel in 0..100) { "Invalid start level" }
        require(input.endLevel == null || input.endLevel in 0..100) { "Invalid end level" }
        require(input.observedMs >= 0 && input.observedMs <= end - input.startTime + 5000) { "Invalid observed interval" }
        require(input.counterCoveredMs in 0..input.observedMs && input.screenOnMs in 0..input.observedMs &&
            input.screenOffMs in 0..(input.observedMs - input.screenOnMs)) { "Incompatible session coverage" }
        require(input.cpuSuspendMs == null || input.cpuSuspendMs in 0..input.observedMs) { "Invalid CPU suspend interval" }
        require(input.deltaUah == null || input.deltaUah in 0..1_000_000_000_000_000L) { "Invalid session charge change" }
        require(input.screenOnUah == null || input.screenOnUah in 0..1_000_000_000_000_000L) { "Invalid screen-on charge" }
        require(input.screenOffUah == null || input.screenOffUah in 0..1_000_000_000_000_000L) { "Invalid screen-off charge" }
        require(input.screenOnMs > 0 || input.screenOnUah == null || input.screenOnUah == 0L) { "Screen-on charge without an observed screen-on interval" }
        require(input.screenOffMs > 0 || input.screenOffUah == null || input.screenOffUah == 0L) { "Screen-off charge without an observed screen-off interval" }
        if (input.observationId != null) {
            require(input.counterCoveredMs > 0 || input.deltaUah == null) { "Charge total without counter coverage" }
            require(input.deltaUah == null || (input.screenOnUah ?: 0) + (input.screenOffUah ?: 0) <= input.deltaUah) { "Screen charge exceeds the session total" }
        }
        require(input.estCapacityMah == null || input.estCapacityMah in 1..200_000) { "Invalid legacy capacity estimate" }
        require(input.lastSampleTime == null || input.lastSampleTime in input.startTime..end) { "Invalid session sample time" }
        return input.copy(sessionId = identity(input.sessionId)!!, observationId = identity(input.observationId),
            endTime = end, activeKey = null, source = source(text(input.source)!!), avgCurrentUa = current(input.avgCurrentUa),
            closeReason = if (input.endTime == null) "Imported snapshot; monitoring was not resumed" else text(input.closeReason))
    }
    fun sameOrigin(first: ChargeSession, second: ChargeSession): Boolean =
        originalId(first.sessionId) == originalId(second.sessionId) && first.startTime == second.startTime && first.type == second.type &&
            first.observationId?.let(::originalId) == second.observationId?.let(::originalId)
}
