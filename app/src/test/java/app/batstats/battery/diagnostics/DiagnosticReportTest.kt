package app.batstats.battery.diagnostics

import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.measurement.ObservationSummary
import org.junit.Assert.*
import org.junit.Test

class DiagnosticReportTest {
    @Test fun missingReadingsAreNotReportedAsZeroAndCountersRetainTheirUnits() {
        assertTrue(DiagnosticReport.reading(null).contains("Charge counter: unavailable"))
        assertTrue(DiagnosticReport.reading(null).contains("Captured: unavailable"))
        val sample = BatterySample(timestamp = 1_000, levelPercent = 0, currentNowUa = -123,
            chargeCounterUah = 0, status = 3, plugged = 0, voltageMv = 4000, temperatureDeciC = 255,
            health = 2, screenOn = true, energyNwh = 1_000_000,
            observationId = "private-observation", sessionId = "private-session")
        val report = DiagnosticReport.reading(sample)
        assertTrue(report.contains("Net current: -123 µA"))
        assertTrue(report.contains("Charge counter: 0 µAh"))
        assertTrue(report.contains("Remaining energy counter: 1000000 nWh"))
        assertTrue(report.contains("Temperature: 25.5 °C"))
        assertTrue(report.contains("1970-01-01T00:00:01Z"))
        assertFalse(report.contains("private-"))
    }
    @Test fun emptyWindowCannotImplyMeasuredCpuSleepOrEnergy() {
        val report = DiagnosticReport.observation(ObservationSummary(), false)
        assertTrue(report.contains("Observed from: unavailable"))
        assertTrue(report.contains("CPU suspend: unavailable"))
        assertTrue(report.contains("Android Doze: unavailable"))
        assertTrue(report.contains("Derived energy estimate: unavailable"))
        assertTrue(report.contains("Monitoring: stopped"))
    }
}
