package app.batstats.battery.data

import app.batstats.battery.data.db.BatterySample

/** A queued database write may finish after a newer capture or a new monitoring generation. */
internal fun mergePersistedReading(current: BatterySample?, persisted: BatterySample): BatterySample? =
    if (current != null && persisted.copy(sessionId = current.sessionId, etaMs = current.etaMs,
            etaBasis = current.etaBasis, boundaryReason = current.boundaryReason) == current) persisted
    else current
