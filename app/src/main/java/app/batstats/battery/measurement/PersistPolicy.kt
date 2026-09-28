package app.batstats.battery.measurement

/** Why a capture becomes a BatterySample row. */
enum class PersistReason {
    /** No earlier row in this monitoring run (first capture, or a new generation). */
    FIRST,
    SCREEN,
    DOZE,
    GAP,

    /** Status or plugged changed: the only kind of change labelled POWER. */
    POWER,
    LEVEL,

    /** A screen-on poll ≥ 30 s after the last row. */
    SCREEN_ON_INTERVAL,

    /** Every screen-off poll. */
    SCREEN_OFF_POLL,
}

/**
 * Which captures are written to history. SCREEN, DOZE and GAP boundaries, status/plugged changes
 * and level changes always are; polls are written every 30 s with the screen on and every time
 * with it off. Anything else — e.g. a voltage- or temperature-only battery broadcast — updates
 * realtime values and alerts only. Realtime-only captures (monitoring off) never reach this.
 */
object PersistPolicy {
    const val SCREEN_ON_SPACING_MS = 30_000L

    /** What is compared between the last persisted sample and a new capture. */
    data class State(
        val elapsedMs: Long,
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
        boundary == Boundary.POWER || last.status != current.status || last.plugged != current.plugged -> PersistReason.POWER
        last.levelPercent != current.levelPercent -> PersistReason.LEVEL
        !poll -> null
        !screenOn -> PersistReason.SCREEN_OFF_POLL
        current.elapsedMs - last.elapsedMs >= SCREEN_ON_SPACING_MS || current.elapsedMs < last.elapsedMs ->
            PersistReason.SCREEN_ON_INTERVAL
        else -> null
    }
}
