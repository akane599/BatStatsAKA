package com.akane.voltwise.battery.measurement

/** Captured data is retained unchanged while a just-observed state awaits its system events. */
data class StateCapture<T>(val value: T, val point: Observation)

/**
 * Main-thread owner, at most one pending capture, no timer or wakeup. A poll/resume can beat
 * SCREEN_ON/POWER/DOZE delivery. Matching events for every changed dimension within two seconds
 * confirm the retained endpoint. An event-backed extension can replace it without restarting
 * the wait; an unconfirmed reversal or expired wait remains a gap.
 */
class StateEventSequencer<T>(private val settleMs: Long = 2_000) {
    private var previous: Observation? = null
    private var pending: StateCapture<T>? = null
    private var pendingSinceMs = 0L
    private var pendingEvents = emptySet<Boundary>()

    fun reset() { previous = null; pending = null; pendingEvents = emptySet() }

    fun offer(value: T, point: Observation): List<StateCapture<T>> {
        val result = mutableListOf<StateCapture<T>>()
        fun emit(capture: StateCapture<T>) { result += capture; previous = capture.point }
        val held = pending
        if (held != null) {
            val age = point.elapsedMs - held.point.elapsedMs
            val timely = point.generation == held.point.generation && age >= 0 &&
                point.elapsedMs - pendingSinceMs in 0..settleMs
            if (timely && point.boundary != Boundary.GAP) {
                pendingEvents = pendingEvents + point.boundary
                // Equal timestamps share one endpoint (Room has a unique elapsed key).
                val endpoint = if (age == 0L) StateCapture(value, point) else held
                if (confirms(previous, endpoint.point, pendingEvents)) {
                    // Keep an existing always-persisted label for screen/Doze compound boundaries.
                    val boundary = when {
                        Boundary.SCREEN in pendingEvents -> Boundary.SCREEN
                        Boundary.DOZE in pendingEvents -> Boundary.DOZE
                        else -> Boundary.POWER
                    }
                    emit(endpoint.copy(point = endpoint.point.copy(boundary = boundary, confirmedBoundaries = pendingEvents)))
                    if (age == 0L) {
                        pending = null
                        pendingEvents = emptySet()
                        return result
                    }
                    // The confirming capture may already show another transition; check it below.
                } else if (age == 0L || sameState(held.point, point)) {
                    pending = endpoint // Keep the first actual reading, except for the shared elapsed key.
                    return emptyList()
                } else if (extendsPending(previous, held.point, point, pendingEvents)) {
                    // Doze can exit before SCREEN arrives. Retain the newer actual reading,
                    // but keep the original deadline and require the still-missing event.
                    pending = StateCapture(value, point)
                    return emptyList()
                } else {
                    emit(held.copy(point = held.point.copy(boundary = Boundary.GAP)))
                }
            } else {
                emit(held.copy(point = held.point.copy(boundary = Boundary.GAP)))
            }
            pending = null
            pendingEvents = emptySet()
        }
        val before = previous
        if (point.boundary != Boundary.GAP && before != null &&
            before.generation == point.generation && point.elapsedMs >= before.elapsedMs &&
            !sameState(before, point) && !confirms(before, point, setOf(point.boundary))) {
            pending = StateCapture(value, point)
            pendingSinceMs = point.elapsedMs
            pendingEvents = setOf(point.boundary)
        } else {
            emit(StateCapture(value, point))
        }
        return result
    }

    private fun sameState(a: Observation, b: Observation) =
        a.interactive == b.interactive && a.power == b.power && a.dozing == b.dozing

    private fun extendsPending(before: Observation?, held: Observation, after: Observation, events: Set<Boundary>): Boolean =
        before != null && confirms(held, after, events) &&
            (before.interactive == held.interactive || held.interactive == after.interactive) &&
            (before.power == held.power || held.power == after.power) &&
            (before.dozing == held.dozing || held.dozing == after.dozing)

    private fun confirms(before: Observation?, after: Observation, events: Set<Boundary>): Boolean =
        before != null && !sameState(before, after) &&
            (before.interactive == after.interactive || Boundary.SCREEN in events) &&
            (before.power == after.power || Boundary.POWER in events) &&
            (before.dozing == after.dozing || Boundary.DOZE in events)
}
