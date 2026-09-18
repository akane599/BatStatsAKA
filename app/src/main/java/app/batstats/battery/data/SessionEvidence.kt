package app.batstats.battery.data

import app.batstats.battery.data.db.ChargeSession

object SessionEvidence {
    fun hasCoverage(session: ChargeSession): Boolean = session.observationId != null &&
        session.source.removePrefix("import:") != "legacy"
    fun isRecording(session: ChargeSession, observationId: String?): Boolean = observationId != null &&
        session.observationId == observationId && session.activeKey == 1 && session.endTime == null
    fun lastEvidence(session: ChargeSession): Long = session.lastSampleTime ?: session.endTime ?: session.startTime
    fun chartBucketMs(session: ChargeSession): Long =
        ((lastEvidence(session) - session.startTime).coerceAtLeast(0) / 360 + 1).coerceAtLeast(1)
}
