package app.batstats.battery.data.sampling

import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.measurement.Boundary
import app.batstats.battery.measurement.Observation
import app.batstats.battery.measurement.ObservationEngine
import app.batstats.battery.measurement.PersistPolicy
import app.batstats.battery.measurement.PowerState
import app.batstats.battery.measurement.StateEventSequencer
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset

class CompoundWakeSessionTest {
    private fun point(delay: Long? = null, boundary: Boundary = Boundary.SAMPLE): Observation {
        val elapsed = delay?.let { 3_600_000 + it } ?: 0L
        return Observation(
            wallMs = 1_000_000 + elapsed, elapsedMs = elapsed, uptimeMs = delay?.let { 1_000 + it } ?: 0L,
            level = 80, chargeUah = if (delay == null) 4_000_000 else 3_900_000,
            currentUa = -100_000, voltageMv = 4000, power = PowerState.DISCHARGING,
            interactive = delay != null, dozing = delay == null, generation = "run", boundary = boundary,
        )
    }

    private fun sample(p: Observation) = BatterySample(
        timestamp = p.wallMs, levelPercent = p.level, status = 3, plugged = 0,
        currentNowUa = p.currentUa, chargeCounterUah = p.chargeUah, voltageMv = p.voltageMv,
        temperatureDeciC = 300, health = 2, screenOn = p.interactive,
        elapsedMs = p.elapsedMs, uptimeMs = p.uptimeMs, observationId = p.generation,
        source = DailySummaryReplay.SAMPLE_SOURCE,
    )

    @Test fun stagedDozeFirstWakeKeepsOneContinuousDischargeSession() {
        val gate = StateEventSequencer<BatterySample>()
        val engine = ObservationEngine()
        val first = point()
        val open = SessionReport.open(first, sample(first))
        var report = open
        var last: PersistPolicy.State? = null
        val saved = mutableListOf<BatterySample>()
        val replay = DailySummaryReplay(ZoneOffset.UTC, 5_000_000)
        val points = listOf(
            first, point(0).copy(dozing = true), point(300, Boundary.DOZE),
            point(600, Boundary.SCREEN), point(3_000),
        )
        for (p in points) {
            for (capture in gate.offer(sample(p), p)) {
                val before = engine.summary
                val after = engine.accept(capture.point)
                assertEquals("Doze-first wake must not trigger gap closure", before.gaps, after.gaps)
                assertEquals("Doze-first wake must not trigger power closure", first.power, capture.point.power)
                assertEquals(open.type, SessionReport.sessionType(capture.point.power))
                val state = PersistPolicy.State(capture.point.elapsedMs, 3, 0, 80, "run")
                if (PersistPolicy.decide(last, state, capture.point.boundary, capture.point.interactive,
                        poll = p.boundary == Boundary.SAMPLE) != null) {
                    report = SessionReport.report(report, capture.value, after, SessionExtremes())
                    saved += capture.value
                    replay.add(capture.value)
                    last = state
                }
            }
        }
        assertEquals(open.sessionId, report.sessionId)
        assertEquals(open.startTime, report.startTime)
        assertNull(report.endTime)
        assertEquals(1, report.activeKey)
        assertEquals(3_600_300L, report.screenOffMs)
        assertEquals(100_000L, report.screenOffUah)
        assertTrue("The newer actual endpoint is saved", saved.any { it.elapsedMs == 3_600_300L })
        assertEquals(saved.size, saved.map { it.elapsedMs }.distinct().size)
        assertTrue(saved.all { it.boundaryReason == null })
        assertEquals(3_600_300L, replay.result.single().screenOffMs)
        assertEquals(100_000L, replay.result.single().screenOffDischargeUah)
    }

    @Test fun confirmedWakeKeepsTheOpenSessionAndPersistedOffIntervalInEitherOrderOrStages() {
        val scenarios = listOf(
            listOf(point(), point(0), point(100, Boundary.SCREEN), point(200, Boundary.DOZE), point(3_000)),
            listOf(point(), point(0), point(100, Boundary.DOZE), point(200, Boundary.SCREEN), point(3_000)),
            listOf(point(), point(0).copy(dozing = true), point(300, Boundary.SCREEN), point(600, Boundary.DOZE), point(3_000)),
        )
        for (points in scenarios) {
            val gate = StateEventSequencer<BatterySample>()
            val engine = ObservationEngine()
            val first = point()
            val open = SessionReport.open(first, sample(first))
            var report = open
            var extremes = SessionExtremes()
            var last: PersistPolicy.State? = null
            val saved = mutableListOf<BatterySample>()
            val replay = DailySummaryReplay(ZoneOffset.UTC, 5_000_000)
            for (p in points) {
                for (capture in gate.offer(sample(p), p)) {
                    val before = engine.summary
                    val after = engine.accept(capture.point)
                    // These are BatteryRepository.process's session-close predicates.
                    assertEquals("Wake must not trigger gap closure", before.gaps, after.gaps)
                    assertEquals("Wake must not trigger power closure", first.power, capture.point.power)
                    assertEquals(open.type, SessionReport.sessionType(capture.point.power))
                    extremes = extremes.plus(null, 300, after.cpuSuspendMs - before.cpuSuspendMs,
                        screenOffBefore = before.latest?.interactive == false)
                    val state = PersistPolicy.State(capture.point.elapsedMs, 3, 0, 80, "run")
                    if (PersistPolicy.decide(last, state, capture.point.boundary, capture.point.interactive,
                            poll = p.boundary == Boundary.SAMPLE) != null) {
                        report = SessionReport.report(report, capture.value, after, extremes)
                        saved += capture.value
                        replay.add(capture.value)
                        last = state
                    }
                }
            }
            assertEquals(open.sessionId, report.sessionId)
            assertEquals(open.startTime, report.startTime)
            assertNull(report.endTime)
            assertEquals(1, report.activeKey)
            assertEquals(3_600_000L, report.screenOffMs)
            assertEquals(100_000L, report.screenOffUah)
            assertEquals(3_599_000L, report.screenOffSuspendMs)
            assertTrue("Confirmed wake endpoint is saved", saved.any { it.elapsedMs == 3_600_000L })
            assertTrue(saved.all { it.boundaryReason == null })
            assertEquals(3_600_000L, replay.result.single().screenOffMs)
            assertEquals(100_000L, replay.result.single().screenOffDischargeUah)
        }
    }
}
