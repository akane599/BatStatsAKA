package app.batstats.battery.measurement

/** Why a capture becomes a BatterySample row. */
enum class PersistReason {
    /** No earlier row in this monitoring run (first capture, or a new generation). */
    FIRST,
    SCREEN,
    DOZE,
    GAP,

    /** Status or plugged changed since the last row (a POWER label alone is not enough). */
    POWER,
    LEVEL,

    /** A screen-on poll ≥ 30 s after the last row. */
    SCREEN_ON_INTERVAL,

    /** A screen-off poll ≥ 30 s after the last row (normally every one: they are 300 s apart). */
    SCREEN_OFF_POLL,
}

/**
 * Which captures are written to history. SCREEN, DOZE and GAP boundaries, status/plugged changes
 * and level changes always are; a poll is written when ≥ 30 s have passed since the last row, on
 * the elapsed clock. With the screen off that is every regular 300 s poll (elapsed time includes
 * deep sleep, so a poll delayed by suspend still saves), while 2 s demand polls that outlive the
 * screen (e.g. a stuck SamplingDemand token) cannot write more than one row per 30 s. Anything
 * else — e.g. a voltage- or temperature-only battery broadcast — updates realtime values and
 * alerts only. Realtime-only captures (monitoring off) never reach this.
 *
 * Boundary.POWER is not a reason by itself: the sampler labels every ACTION_BATTERY_CHANGED
 * capture POWER (unchanged-state ones pass through StateEventSequencer with that label), so only
 * an actual status or plugged difference from the last row counts.
 */
object PersistPolicy {
    /** Minimum elapsed time between rows saved for polls alone, screen on or off. */
    const val POLL_SPACING_MS = 30_000L

    /** What is compared between the last persisted sample and a new capture. */
    data class State(
        val elapsedMs: Long,     // SystemClock.elapsedRealtime (counts deep sleep), i.e. Observation.elapsedMs
        val status: Int,         // BatteryManager status
        val plugged: Int?,       // BatteryManager EXTRA_PLUGGED
        val levelPercent: Int?,
        val generation: String?, // observation/monitoring run id
    )

    /**
     * @param last the last persisted sample, null when none in this run
     * @param boundary the capture's (sequenced) boundary
     * @param screenOn interactive at capture time
     * @param poll true for a cadence poll or handoff capture, false for a broadcast or event
     * @return why to persist, or null to keep the capture realtime-only
     */
    fun decide(last: State?, current: State, boundary: Boundary, screenOn: Boolean, poll: Boolean): PersistReason? = when {
        last == null || last.generation != current.generation -> PersistReason.FIRST
        boundary == Boundary.SCREEN -> PersistReason.SCREEN
        boundary == Boundary.DOZE -> PersistReason.DOZE
        boundary == Boundary.GAP -> PersistReason.GAP
        last.status != current.status || last.plugged != current.plugged -> PersistReason.POWER
        last.levelPercent != current.levelPercent -> PersistReason.LEVEL
        !poll -> null
        // A clock that ran backwards (it cannot on one generation) never suppresses a save.
        current.elapsedMs >= last.elapsedMs && current.elapsedMs - last.elapsedMs < POLL_SPACING_MS -> null
        !screenOn -> PersistReason.SCREEN_OFF_POLL
        else -> PersistReason.SCREEN_ON_INTERVAL
    }
}
