package app.batstats.battery.data

import app.batstats.battery.data.db.ChargeSession

object SessionEvidence {
    fun hasCoverage(session: ChargeSession): Boolean = session.observationId != null &&
        session.source.removePrefix("import:") != "legacy"
    fun isRecording(session: ChargeSession, observationId: String?): Boolean = observationId != null &&
        session.observationId == observationId && session.activeKey == 1 && session.endTime == null
    /**
     * The counter's charge moved (µAh, as stored) when it can be shown: this app measured the session ([hasCoverage],
     * not a legacy row) and the charge counter covered some of it; null otherwise. One rule for History's rows and
     * SessionDetails' header, so a figure one hides never shows in the other.
     */
    fun measuredChargeUah(session: ChargeSession): Long? =
        session.deltaUah?.takeIf { hasCoverage(session) && session.counterCoveredMs > 0 }
    fun lastEvidence(session: ChargeSession): Long = session.lastSampleTime ?: session.endTime ?: session.startTime
    fun chartBucketMs(session: ChargeSession): Long =
        ((lastEvidence(session) - session.startTime).coerceAtLeast(0) / 360 + 1).coerceAtLeast(1)
}
