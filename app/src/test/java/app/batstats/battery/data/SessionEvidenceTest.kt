package app.batstats.battery.data

import app.batstats.battery.data.db.*
import org.junit.Assert.*
import org.junit.Test

class SessionEvidenceTest {
    private fun session() = ChargeSession("s", SessionType.DISCHARGE, 1000, null, 80, 80, null, null, null,
        lastSampleTime = 61_000, observationId = "current", source = "BatteryManager observed interval")
    @Test fun importedLegacyRecordsNeverGainCoverageFromDefaultZeroFields() {
        val legacy = session().copy(source = "import:legacy", observationId = "import:unknown")
        assertFalse(SessionEvidence.hasCoverage(legacy))
        assertFalse(SessionEvidence.hasCoverage(session().copy(observationId = null)))
        assertTrue(SessionEvidence.hasCoverage(session().copy(source = "import:BatteryManager observed interval")))
    }
    @Test fun staleOrImportedActiveRecordCannotClaimMonitoringIsRunning() {
        assertFalse(SessionEvidence.isRecording(session(), null))
        assertFalse(SessionEvidence.isRecording(session(), "new-observation"))
        assertFalse(SessionEvidence.isRecording(session().copy(activeKey = null), "current"))
        assertTrue(SessionEvidence.isRecording(session(), "current"))
    }
    @Test fun chartsUseLastEvidenceWithoutExtendingStoppedObservationToNow() {
        val session = session()
        assertEquals(61_000L, SessionEvidence.lastEvidence(session))
        assertTrue((SessionEvidence.lastEvidence(session) - session.startTime) / SessionEvidence.chartBucketMs(session) <= 360)
        assertEquals(1000L, SessionEvidence.lastEvidence(session.copy(lastSampleTime = null)))
    }
}
