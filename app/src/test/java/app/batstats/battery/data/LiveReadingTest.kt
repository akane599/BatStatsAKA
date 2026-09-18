package app.batstats.battery.data

import app.batstats.battery.data.db.BatterySample
import org.junit.Assert.*
import org.junit.Test

class LiveReadingTest {
    private val captured = BatterySample(timestamp = 1_000_000, levelPercent = 50, status = 3, plugged = 0,
        currentNowUa = -50_000, chargeCounterUah = 2_000_000, voltageMv = 4000, temperatureDeciC = 250,
        health = 2, screenOn = true, elapsedMs = 10_000, observationId = "first")
    @Test fun queuedWriteCannotOverwriteNewerCaptureOrMonitoringGeneration() {
        val persisted = captured.copy(sessionId = "session", etaMs = 100_000)
        val newer = captured.copy(timestamp = 1_030_000, elapsedMs = 40_000, levelPercent = 49, screenOn = false)
        assertSame(newer, mergePersistedReading(newer, persisted))
        val restarted = captured.copy(observationId = "second")
        assertSame(restarted, mergePersistedReading(restarted, persisted))
        val sameMillisecondTransition = captured.copy(screenOn = false, status = 2, plugged = 1)
        assertSame(sameMillisecondTransition, mergePersistedReading(sameMillisecondTransition, persisted))
        assertNull(mergePersistedReading(null, persisted))
    }
    @Test fun matchingCaptureReceivesPersistedSessionAndEstimate() {
        val persisted = captured.copy(sessionId = "session", etaMs = 100_000)
        assertSame(persisted, mergePersistedReading(captured, persisted))
    }
}
