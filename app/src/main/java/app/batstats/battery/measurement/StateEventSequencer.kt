package app.batstats.battery.measurement

/** Captured data is retained unchanged while a just-observed state awaits its system events. */
data class StateCapture<T>(val value: T, val point: Observation)

/**
 * Main-thread owner, at most one pending capture, no timer or wakeup. A poll/resume can beat
 * SCREEN_ON/POWER/DOZE delivery. Matching events for every changed dimension within two seconds
 * confirm the first observed endpoint; otherwise the unconfirmed interval remains a gap.
 */
class StateEventSequencer<T>(private val settleMs: Long = 2_000) {
    private var previous: Observation? = null
    private var pending: StateCapture<T>? = null
    private var pendingEvents = emptySet<Boundary>()

    fun reset() { previous = null; pending = null; pendingEvents = emptySet() }

    fun offer(value: T, point: Observation): List<StateCapture<T>> {
        val result = mutableListOf<StateCapture<T>>()
        fun emit(capture: StateCapture<T>) { result += capture; previous = capture.point }
        val held = pending
        if (held != null) {
            val age = point.elapsedMs - held.point.elapsedMs
            val timely = point.generation == held.point.generation && age in 0..settleMs
            if (timely && sameState(held.point, point) && point.boundary != Boundary.GAP) {
                pendingEvents = pendingEvents + point.boundary
                if (!confirms(previous, held.point, pendingEvents)) {
                    return emptyList() // Keep the first actual reading, not a later inferred endpoint.
                }
                // Keep an existing always-persisted label for screen/Doze compound boundaries.
                val boundary = when {
                    Boundary.SCREEN in pendingEvents -> Boundary.SCREEN
                    Boundary.DOZE in pendingEvents -> Boundary.DOZE
                    else -> Boundary.POWER
                }
                // Equal timestamps need only the event capture (Room has a unique elapsed key).
                val endpoint = if (age == 0L) StateCapture(value, point) else held
                emit(endpoint.copy(point = endpoint.point.copy(boundary = boundary, confirmedBoundaries = pendingEvents)))
                pending = null
                pendingEvents = emptySet()
                if (age > 0) emit(StateCapture(value, point))
                return result
            }
            emit(held.copy(point = held.point.copy(boundary = Boundary.GAP)))
            pending = null
            pendingEvents = emptySet()
        }
        val before = previous
        if (point.boundary != Boundary.GAP && before != null &&
            before.generation == point.generation && point.elapsedMs >= before.elapsedMs &&
            !sameState(before, point) && !confirms(before, point, setOf(point.boundary))) {
            pending = StateCapture(value, point)
            pendingEvents = setOf(point.boundary)
        } else {
            emit(StateCapture(value, point))
        }
        return result
    }

    private fun sameState(a: Observation, b: Observation) =
        a.interactive == b.interactive && a.power == b.power && a.dozing == b.dozing

    private fun confirms(before: Observation?, after: Observation, events: Set<Boundary>): Boolean =
        before != null && !sameState(before, after) &&
            (before.interactive == after.interactive || Boundary.SCREEN in events) &&
            (before.power == after.power || Boundary.POWER in events) &&
            (before.dozing == after.dozing || Boundary.DOZE in events)
}
