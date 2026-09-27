package app.batstats.battery.measurement

/** Captured data is retained unchanged while a just-observed state awaits its system event. */
data class StateCapture<T>(val value: T, val point: Observation)

/**
 * Main-thread owner, at most one pending capture, no timer or wakeup. A poll/resume can beat
 * SCREEN_ON/POWER/DOZE delivery. A matching event within two seconds confirms the first
 * observed endpoint; otherwise the unconfirmed interval remains a gap.
 */
class StateEventSequencer<T>(private val settleMs: Long = 2_000) {
    private var previous: Observation? = null
    private var pending: StateCapture<T>? = null

    fun reset() { previous = null; pending = null }

    fun offer(value: T, point: Observation): List<StateCapture<T>> {
        val result = mutableListOf<StateCapture<T>>()
        fun emit(capture: StateCapture<T>) { result += capture; previous = capture.point }
        val held = pending
        if (held != null) {
            val age = point.elapsedMs - held.point.elapsedMs
            val timely = point.generation == held.point.generation && age in 0..settleMs
            val same = sameState(held.point, point)
            if (timely && same && confirms(previous, held.point, point.boundary)) {
                // Equal timestamps need only the event capture (Room has a unique elapsed key).
                if (age > 0) emit(held.copy(point = held.point.copy(boundary = point.boundary)))
                pending = null
            } else if (timely && same && point.boundary != Boundary.GAP) {
                return emptyList() // Keep the first actual reading, not a later inferred endpoint.
            } else {
                emit(held.copy(point = held.point.copy(boundary = Boundary.GAP)))
                pending = null
            }
        }
        val before = previous
        if (point.boundary != Boundary.GAP && before != null &&
            before.generation == point.generation && point.elapsedMs >= before.elapsedMs &&
            !sameState(before, point) && !confirms(before, point, point.boundary)) {
            pending = StateCapture(value, point)
        } else {
            emit(StateCapture(value, point))
        }
        return result
    }

    private fun sameState(a: Observation, b: Observation) =
        a.interactive == b.interactive && a.power == b.power && a.dozing == b.dozing

    private fun confirms(before: Observation?, after: Observation, boundary: Boundary): Boolean {
        if (before == null) return false
        return when (boundary) {
            Boundary.SCREEN -> before.interactive != after.interactive && before.power == after.power && before.dozing == after.dozing
            Boundary.POWER -> before.power != after.power && before.interactive == after.interactive && before.dozing == after.dozing
            Boundary.DOZE -> before.dozing != after.dozing && before.interactive == after.interactive && before.power == after.power
            else -> false
        }
    }
}
