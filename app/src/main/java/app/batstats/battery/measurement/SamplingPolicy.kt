package app.batstats.battery.measurement

/**
 * Capture cadence: 2 s while a SamplingDemand token is held (Now, an active session's details,
 * the tile with the shade open), 30 s with the screen on, 300 s with it off. Delays are
 * uptime-based (`Handler.postDelayed`), so a pending poll never wakes a sleeping CPU; broadcasts
 * still capture while it sleeps. With monitoring off, only demand polls, for realtime values.
 *
 * Handoff: stamp every capture via [stamp] (the interval that follows a capture in that state)
 * and schedule the next poll with the same value. When the interval changes between captures —
 * demand acquired or released, monitoring toggled — [needsHandoffCapture] says to capture now
 * rather than wait, so the capture before a longer wait always carries the longer interval and
 * ObservationEngine does not flag that wait as a gap. Screen changes arrive as SCREEN captures,
 * which [stamp] already gives the new interval.
 */
object SamplingPolicy {
    const val REALTIME_INTERVAL_MS = 2_000L
    const val SCREEN_ON_INTERVAL_MS = 30_000L
    const val SCREEN_OFF_INTERVAL_MS = 300_000L

    /** The interval that follows a capture taken in this state (its `expectedIntervalMs`). */
    fun expectedIntervalMs(demandHeld: Boolean, screenOn: Boolean): Long = when {
        demandHeld -> REALTIME_INTERVAL_MS
        screenOn -> SCREEN_ON_INTERVAL_MS
        else -> SCREEN_OFF_INTERVAL_MS
    }

    /**
     * Uptime delay until the next poll, or null for no polling (monitoring off and no demand).
     * With monitoring off, demand polls are realtime-only: never observed or persisted.
     */
    fun pollIntervalMs(monitoring: Boolean, demandHeld: Boolean, screenOn: Boolean): Long? =
        if (monitoring || demandHeld) expectedIntervalMs(demandHeld, screenOn) else null

    /** [point] with `expectedIntervalMs` for the demand and screen state at capture time. */
    fun stamp(point: Observation, demandHeld: Boolean): Observation =
        point.copy(expectedIntervalMs = expectedIntervalMs(demandHeld, point.interactive))

    /**
     * True when [newIntervalMs] (from [pollIntervalMs]) replaces the scheduled one: capture now,
     * stamped with the new state, and reschedule. A null new interval just cancels polling.
     */
    fun needsHandoffCapture(scheduledIntervalMs: Long?, newIntervalMs: Long?): Boolean =
        newIntervalMs != null && newIntervalMs != scheduledIntervalMs
}
