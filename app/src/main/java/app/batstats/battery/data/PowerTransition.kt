package app.batstats.battery.data

import app.batstats.battery.measurement.PowerState

/**
 * A plug-in or unplug seen by the sampler, emitted after the session boundary is persisted.
 * [endedSessionId] is the session that just closed (null when none was open).
 */
data class PowerTransition(
    val from: PowerState,
    val to: PowerState,
    val wallTimeMs: Long,
    val elapsedMs: Long,
    val endedSessionId: String?,
    val startedSessionId: String?,
)
