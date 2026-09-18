package app.batstats.battery.measurement

/** Estimates need a continuous, stable discharge window; no assumed design capacity. */
class RemainingTimeEstimator {
    data class Estimate(val remainingMs: Long, val observedMs: Long)
    private val points = ArrayDeque<Observation>()

    fun reset() = points.clear()

    fun accept(point: Observation): Estimate? {
        val previous = points.lastOrNull()
        if (point.power != PowerState.DISCHARGING || point.chargeUah == null) {
            reset()
            return null
        }
        if (previous != null && (previous.generation != point.generation ||
                point.elapsedMs <= previous.elapsedMs || point.boundary == Boundary.GAP ||
                point.elapsedMs - previous.elapsedMs > maxOf(120_000, previous.expectedIntervalMs * 3) ||
                BatteryReading.dischargedUah(previous.chargeUah, point.chargeUah, point.elapsedMs - previous.elapsedMs) == null)) {
            reset()
        }
        points.addLast(point)
        while (points.size > 400 || point.elapsedMs - points.first().elapsedMs > 30 * 60_000) points.removeFirst()
        if (points.size < 5) return null
        val first = points.first()
        val duration = point.elapsedMs - first.elapsedMs
        if (duration < 10 * 60_000) return null
        val middle = points.elementAt(points.size / 2)
        val firstDelta = first.chargeUah!! - middle.chargeUah!!
        val lastDelta = middle.chargeUah - point.chargeUah
        if (firstDelta < 5_000 || lastDelta < 5_000) return null
        val firstRate = firstDelta.toDouble() / (middle.elapsedMs - first.elapsedMs)
        val lastRate = lastDelta.toDouble() / (point.elapsedMs - middle.elapsedMs)
        if (lastRate / firstRate !in 0.5..2.0) return null
        val remainingMs = point.chargeUah * duration.toDouble() / (firstDelta + lastDelta)
        return remainingMs.takeIf { it.isFinite() && it in 60_000.0..7 * 86_400_000.0 }
            ?.let { Estimate(it.toLong(), duration) }
    }
}
