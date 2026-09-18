package app.batstats.battery.drain

import app.batstats.battery.measurement.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class MonitoringTextTest {
    private fun point(t: Long, counter: Long?, screen: Boolean = true, boundary: Boundary = Boundary.SAMPLE) =
        Observation(1_000_000 + t, t, t, 80, counter, -60_000, 4000, PowerState.DISCHARGING, screen, false, "observation", boundary = boundary)
    private fun english(block: () -> Unit) {
        val old = Locale.getDefault()
        try { Locale.setDefault(Locale.US); block() } finally { Locale.setDefault(old) }
    }
    @Test fun screenNeverTurnedOffDoesNotCreateAnIdleRateInNotificationText() = english {
        val engine = ObservationEngine()
        engine.accept(point(0, 4_000_000))
        val summary = engine.accept(point(60_000, 3_999_000))
        val text = MonitoringText.expanded(summary)
        assertTrue(text.contains("Screen on: 60 mA · 1.0 mAh · 1m 0s"))
        assertTrue(text.contains("Screen off: — · — · 0s"))
        assertFalse(text.contains("Idle:"))
        assertTrue(text.contains("Discharge: 60 mA · 1.0 mAh · 1m 0s"))
    }
    @Test fun missingCountersAndResetNeverRetainPreviousConsumption() = english {
        val engine = ObservationEngine()
        engine.accept(point(0, 4_000_000))
        engine.accept(point(60_000, 3_999_000))
        engine.reset()
        engine.accept(point(120_000, null))
        val summary = engine.accept(point(180_000, null))
        assertEquals(1_120_000L, summary.startedAt)
        assertTrue(MonitoringText.expanded(summary).contains("Discharge: — · — · 1m 0s"))
        assertEquals("Charge unavailable", MonitoringText.coverage(summary.discharge))
    }
    @Test fun noObservationAndMeasuredZeroRemainDistinctForCpuAndDoze() {
        val empty = ObservationSummary()
        assertTrue(MonitoringText.cpuSuspend(empty).contains("—"))
        assertTrue(MonitoringText.doze(empty).contains("—"))
        val observed = empty.copy(cpuObservedMs = 60_000)
        assertEquals("0s / 1m 0s observed", MonitoringText.cpuSuspend(observed))
        assertEquals("0s", MonitoringText.doze(observed))
    }
}
