package app.batstats.battery.measurement

import org.junit.Assert.*
import org.junit.Test

class CompoundBoundaryTest {
    private fun baseline() = Observation(
        1_000_000, 0, 0, 80, 4_000_000, -100_000, 4000,
        PowerState.DISCHARGING, false, true, "run",
    )

    private fun wake(delay: Long = 0, boundary: Boundary = Boundary.SAMPLE) = baseline().copy(
        wallMs = 4_600_000 + delay, elapsedMs = 3_600_000 + delay, uptimeMs = 1_000 + delay,
        chargeUah = 3_900_000, interactive = true, dozing = false, boundary = boundary,
    )

    @Test fun bothWakeEventsPreserveSleepAndChargeInEitherOrder() {
        for (order in listOf(listOf(Boundary.SCREEN, Boundary.DOZE), listOf(Boundary.DOZE, Boundary.SCREEN))) {
            val gate = StateEventSequencer<String>()
            val engine = ObservationEngine()
            gate.offer("baseline", baseline()).forEach { engine.accept(it.point) }
            assertTrue(gate.offer("first wake", wake()).isEmpty())
            assertTrue(gate.offer("first event", wake(100, order[0])).isEmpty())
            val confirmed = gate.offer("second event", wake(200, order[1]))
            assertEquals("Keep the actual first endpoint", listOf("first wake", "second event"), confirmed.map { it.value })
            confirmed.forEach { engine.accept(it.point) }
            gate.offer("later poll", wake(3_000)).forEach { engine.accept(it.point) }
            assertEquals("No gap on confirmed compound wake", 0, engine.summary.gaps)
            assertEquals(3_600_000L, engine.summary.screenOff.durationMs)
            assertEquals(3_600_000L, engine.summary.screenOff.chargeCoveredMs)
            assertEquals(100_000L, engine.summary.screenOff.chargeChangeUah)
            assertEquals(3_599_000L, engine.summary.cpuSuspendMs)
            assertEquals(3_600_000L, engine.summary.dozeMs)
            assertEquals(3_000L, engine.summary.screenOn.durationMs)
        }
    }

    @Test fun screenEventConfirmsHeldWakeBeforeDozeExitIsConfirmed() {
        val gate = StateEventSequencer<String>()
        val engine = ObservationEngine()
        gate.offer("baseline", baseline()).forEach { engine.accept(it.point) }
        assertTrue(gate.offer("screen reading", wake().copy(dozing = true)).isEmpty())
        val screen = gate.offer("doze reading", wake(300, Boundary.SCREEN))
        screen.forEach { engine.accept(it.point) }
        assertEquals("Confirmed screen endpoint must not become a gap", 0, engine.summary.gaps)
        assertEquals(listOf("screen reading"), screen.map { it.value })
        assertEquals(Boundary.SCREEN, screen.single().point.boundary)
        assertEquals(setOf(Boundary.SAMPLE, Boundary.SCREEN), screen.single().point.confirmedBoundaries)
        assertEquals(3_600_000L, engine.summary.screenOff.durationMs)
        assertEquals(100_000L, engine.summary.screenOff.chargeChangeUah)
        val doze = gate.offer("doze event", wake(600, Boundary.DOZE))
        assertEquals(listOf("doze reading", "doze event"), doze.map { it.value })
        doze.forEach { engine.accept(it.point) }
        assertEquals(0, engine.summary.gaps)
        assertEquals(3_600_300L, engine.summary.dozeMs)
        assertEquals(600L, engine.summary.screenOn.durationMs)
    }

    @Test fun stagedWakeDozeEventBeforeScreenEventKeepsNoGap() {
        val gate = StateEventSequencer<String>()
        val engine = ObservationEngine()
        gate.offer("baseline", baseline()).forEach { engine.accept(it.point) }
        assertTrue(gate.offer("screen reading", wake().copy(dozing = true)).isEmpty())
        val doze = gate.offer("doze reading", wake(300, Boundary.DOZE))
        doze.forEach { engine.accept(it.point) }
        val screen = gate.offer("screen event", wake(600, Boundary.SCREEN))
        screen.forEach { engine.accept(it.point) }
        assertEquals("Doze-first staged wake must not become a gap", 0, engine.summary.gaps)
        assertTrue("Keep waiting for SCREEN", doze.isEmpty())
        assertEquals(listOf("doze reading", "screen event"), screen.map { it.value })
        assertEquals(setOf(Boundary.SAMPLE, Boundary.DOZE, Boundary.SCREEN), screen.first().point.confirmedBoundaries)
        assertEquals(3_600_300L, engine.summary.screenOff.durationMs)
        assertEquals(100_000L, engine.summary.screenOff.chargeChangeUah)
        assertEquals(3_599_000L, engine.summary.cpuSuspendMs)
        assertEquals(3_600_300L, engine.summary.dozeMs)
        assertEquals(300L, engine.summary.screenOn.durationMs)
        assertEquals(screen.size, screen.map { it.point.elapsedMs }.distinct().size)
    }

    @Test fun extendedWakeKeepsItsOriginalDeadlineAndFirstSameStateReading() {
        for (delay in listOf(2_000L, 2_001L)) {
            val gate = StateEventSequencer<String>()
            gate.offer("baseline", baseline())
            gate.offer("screen reading", wake().copy(dozing = true))
            assertTrue(gate.offer("doze reading", wake(300, Boundary.DOZE)).isEmpty())
            assertTrue(gate.offer("same state", wake(1_000)).isEmpty())
            val result = gate.offer("screen event", wake(delay, Boundary.SCREEN))
            assertEquals(listOf("doze reading", "screen event"), result.map { it.value })
            assertEquals(if (delay == 2_000L) Boundary.SCREEN else Boundary.GAP, result.first().point.boundary)
        }
    }

    @Test fun extendedWakeRejectsGapGenerationChangeBackwardsClockAndReversal() {
        for (next in listOf(
            wake(600, Boundary.GAP),
            wake(600, Boundary.SCREEN).copy(generation = "new run"),
            wake(200, Boundary.SCREEN),
            wake(600).copy(interactive = false),
        )) {
            val gate = StateEventSequencer<Unit>()
            gate.offer(Unit, baseline())
            gate.offer(Unit, wake().copy(dozing = true))
            assertTrue(gate.offer(Unit, wake(300, Boundary.DOZE)).isEmpty())
            assertEquals(Boundary.GAP, gate.offer(Unit, next).first().point.boundary)
        }
    }

    @Test fun extendedWakeAtEqualTimestampEmitsOnlyOneEndpoint() {
        val gate = StateEventSequencer<String>()
        gate.offer("baseline", baseline())
        gate.offer("screen reading", wake().copy(dozing = true))
        assertTrue(gate.offer("doze reading", wake(300, Boundary.DOZE)).isEmpty())
        val result = gate.offer("screen event", wake(300, Boundary.SCREEN))
        assertEquals(listOf("screen event"), result.map { it.value })
        assertEquals(setOf(Boundary.SAMPLE, Boundary.DOZE, Boundary.SCREEN), result.single().point.confirmedBoundaries)
    }

    @Test fun extensionCannotHideANewUnconfirmedDimensionOrReverseAnEarlierConfirmedOne() {
        val gate = StateEventSequencer<Unit>()
        gate.offer(Unit, baseline())
        gate.offer(Unit, wake().copy(dozing = true))
        assertEquals(Boundary.GAP, gate.offer(Unit, wake(300, Boundary.DOZE).copy(power = PowerState.CHARGING)).first().point.boundary)

        gate.reset()
        gate.offer(Unit, baseline())
        gate.offer(Unit, wake())
        gate.offer(Unit, wake(100, Boundary.SCREEN))
        val reversed = gate.offer(Unit, wake(300, Boundary.POWER).copy(interactive = false, power = PowerState.CHARGING))
        assertEquals("Earlier SCREEN evidence cannot erase its reversed transition", Boundary.GAP, reversed.first().point.boundary)
    }

    @Test fun stagedWakeWithoutTimelyScreenEventStillLeavesAGap() {
        for (event in listOf(wake(300, Boundary.DOZE), wake(2_001, Boundary.SCREEN))) {
            val gate = StateEventSequencer<Unit>()
            val engine = ObservationEngine()
            listOf(baseline(), wake().copy(dozing = true), event, wake(2_600, Boundary.DOZE)).forEach { p ->
                gate.offer(Unit, p).forEach { engine.accept(it.point) }
            }
            assertEquals(1, engine.summary.gaps)
            assertEquals(0L, engine.summary.screenOff.durationMs)
        }
    }

    @Test fun stagedWakeStillRequiresTheLaterDozeEvent() {
        val gate = StateEventSequencer<Unit>()
        val engine = ObservationEngine()
        listOf(baseline(), wake().copy(dozing = true), wake(300, Boundary.SCREEN), wake(2_301)).forEach { p ->
            gate.offer(Unit, p).forEach { engine.accept(it.point) }
        }
        assertEquals(1, engine.summary.gaps)
        assertEquals("Only the confirmed screen interval is retained", 3_600_000L, engine.summary.screenOff.durationMs)
        assertEquals(3_600_000L, engine.summary.dozeMs)
    }

    @Test fun stagedWakeAtEqualTimestampWaitsForAllEventsWithoutDuplicateRows() {
        val gate = StateEventSequencer<String>()
        val engine = ObservationEngine()
        gate.offer("baseline", baseline()).forEach { engine.accept(it.point) }
        assertTrue(gate.offer("screen reading", wake().copy(dozing = true)).isEmpty())
        assertTrue(gate.offer("doze reading", wake(boundary = Boundary.SCREEN)).isEmpty())
        val confirmed = gate.offer("doze event", wake(300, Boundary.DOZE))
        assertEquals(listOf("doze reading", "doze event"), confirmed.map { it.value })
        assertEquals(confirmed.size, confirmed.map { it.point.elapsedMs }.distinct().size)
        confirmed.forEach { engine.accept(it.point) }
        assertEquals(0, engine.summary.gaps)
        assertEquals(3_600_000L, engine.summary.screenOff.durationMs)
    }

    @Test fun eitherMissingWakeEventStillLeavesAGap() {
        for (event in listOf(Boundary.SCREEN, Boundary.DOZE)) {
            val gate = StateEventSequencer<Unit>()
            val engine = ObservationEngine()
            listOf(baseline(), wake(), wake(100, event), wake(200, event), wake(3_000)).forEach { p ->
                gate.offer(Unit, p).forEach { engine.accept(it.point) }
            }
            assertEquals(1, engine.summary.gaps)
            assertEquals(0L, engine.summary.screenOff.durationMs)
            assertEquals(0L, engine.summary.screenOff.chargeCoveredMs)
        }
    }

    @Test fun firstCaptureEventCountsAndEqualTimestampsEmitOnlyOneEndpoint() {
        val gate = StateEventSequencer<Int>()
        val engine = ObservationEngine()
        gate.offer(0, baseline()).forEach { engine.accept(it.point) }
        assertTrue(gate.offer(1, wake(boundary = Boundary.DOZE)).isEmpty())
        val confirmed = gate.offer(2, wake(boundary = Boundary.SCREEN))
        assertEquals(listOf(2), confirmed.map { it.value })
        confirmed.forEach { engine.accept(it.point) }
        assertEquals(0, engine.summary.gaps)
        assertEquals(3_600_000L, engine.summary.screenOff.durationMs)
    }

    @Test fun unconfirmedStateChangeOrLateWakeEventCannotCompleteConfirmation() {
        for (last in listOf(wake(200, Boundary.GAP), wake(200).copy(interactive = false), wake(2_001, Boundary.DOZE))) {
            val gate = StateEventSequencer<Unit>()
            gate.offer(Unit, baseline())
            gate.offer(Unit, wake())
            gate.offer(Unit, wake(100, Boundary.SCREEN))
            assertEquals(Boundary.GAP, gate.offer(Unit, last).first().point.boundary)
        }
    }

    @Test fun confirmingEventCanExposeAnUnconfirmedReverseTransition() {
        val gate = StateEventSequencer<Unit>()
        val engine = ObservationEngine()
        listOf(baseline(), wake(), wake(100, Boundary.SCREEN)).forEach { p ->
            gate.offer(Unit, p).forEach { engine.accept(it.point) }
        }
        val confirmed = gate.offer(Unit, wake(200, Boundary.DOZE).copy(interactive = false))
        assertEquals(listOf(Boundary.SCREEN), confirmed.map { it.point.boundary })
        confirmed.forEach { engine.accept(it.point) }
        assertEquals(0, engine.summary.gaps)
        assertEquals(3_600_000L, engine.summary.screenOff.durationMs)
        val unconfirmed = gate.offer(Unit, wake(2_201).copy(interactive = false))
        assertEquals(Boundary.GAP, unconfirmed.first().point.boundary)
        unconfirmed.forEach { engine.accept(it.point) }
        assertEquals("Earlier SCREEN must not confirm the later reversal", 1, engine.summary.gaps)
    }

    @Test fun explicitGapOverridesCompoundEvidenceAndRawOneKindIsInsufficient() {
        val gate = StateEventSequencer<Unit>()
        gate.offer(Unit, baseline())
        gate.offer(Unit, wake(boundary = Boundary.SCREEN))
        val confirmed = gate.offer(Unit, wake(100, Boundary.DOZE)).first().point
        for (unconfirmed in listOf(confirmed.copy(boundary = Boundary.GAP), wake(boundary = Boundary.SCREEN))) {
            val engine = ObservationEngine()
            engine.accept(baseline())
            engine.accept(unconfirmed)
            assertEquals(1, engine.summary.gaps)
            assertEquals(0L, engine.summary.observedMs)
        }
    }

    @Test fun confirmedEventsDoNotLeakIntoTheNextTransition() {
        val gate = StateEventSequencer<Unit>()
        val engine = ObservationEngine()
        val points = listOf(
            baseline(), wake(), wake(100, Boundary.SCREEN), wake(200, Boundary.DOZE),
            wake(500, Boundary.DOZE).copy(interactive = false, dozing = true),
            wake(3_000).copy(interactive = false, dozing = true),
        )
        points.forEach { p -> gate.offer(Unit, p).forEach { engine.accept(it.point) } }
        assertEquals("The second screen event is missing", 1, engine.summary.gaps)
        assertEquals(3_602_500L, engine.summary.screenOff.durationMs)
    }

    @Test fun powerScreenAndDozeRequireAllThreeEvents() {
        val gate = StateEventSequencer<Unit>()
        val engine = ObservationEngine()
        gate.offer(Unit, baseline()).forEach { engine.accept(it.point) }
        fun powered(delay: Long, event: Boundary) = wake(delay, event).copy(power = PowerState.CHARGING)
        assertTrue(gate.offer(Unit, powered(0, Boundary.POWER)).isEmpty())
        assertTrue(gate.offer(Unit, powered(100, Boundary.SCREEN)).isEmpty())
        val confirmed = gate.offer(Unit, powered(200, Boundary.DOZE))
        assertEquals(2, confirmed.size)
        confirmed.forEach { engine.accept(it.point) }
        assertEquals(0, engine.summary.gaps)
        assertEquals(3_600_000L, engine.summary.screenOff.durationMs)
        assertEquals("Cross-power charge remains unavailable", 0L, engine.summary.screenOff.chargeCoveredMs)
    }
}
