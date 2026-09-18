package app.batstats.battery.measurement

import org.junit.Assert.*
import org.junit.Test

class StateEventSequencerTest {
    private fun point(t: Long, screen: Boolean = true, power: PowerState = PowerState.DISCHARGING,
                      boundary: Boundary = Boundary.SAMPLE, generation: String = "one", doze: Boolean = false) =
        Observation(1_000_000 + t, t, t, 80, 4_000_000 - t, -60_000, 4000,
            power, screen, doze, generation, boundary = boundary)

    @Test fun pollBeforeWakeBroadcastPreservesTheOffIntervalAndActualEndpoint() {
        val gate = StateEventSequencer<String>()
        val engine = ObservationEngine()
        fun offer(value: String, point: Observation) = gate.offer(value, point).also { captures -> captures.forEach { engine.accept(it.point) } }
        offer("start", point(0))
        offer("off", point(1_000, screen = false, boundary = Boundary.SCREEN))
        assertTrue(offer("first on reading", point(60_000)).isEmpty())
        assertEquals(0L, engine.summary.screenOff.durationMs)
        val confirmed = offer("broadcast", point(60_100, boundary = Boundary.SCREEN))
        assertEquals(listOf("first on reading", "broadcast"), confirmed.map { it.value })
        assertEquals(59_000L, engine.summary.screenOff.durationMs)
        assertEquals(1_100L, engine.summary.screenOn.durationMs)
        assertEquals(59.0, engine.summary.screenOff.chargeMah!!, 0.001)
        assertEquals(0, engine.summary.gaps)
    }

    @Test fun absentEventExcludesTheUnconfirmedIntervalInsteadOfInventingCoverage() {
        val gate = StateEventSequencer<Unit>()
        val engine = ObservationEngine()
        listOf(point(0), point(1_000, screen = false), point(4_000, screen = false)).forEach { p ->
            gate.offer(Unit, p).forEach { engine.accept(it.point) }
        }
        assertEquals(1, engine.summary.gaps)
        assertEquals(0L, engine.summary.screenOn.durationMs)
        assertEquals(3_000L, engine.summary.screenOff.durationMs)
        assertEquals(3.0, engine.summary.screenOff.chargeMah!!, 0.001)
    }

    @Test fun unrelatedBatteryEventCannotConfirmScreenStateButDoesNotEraseItsPendingReading() {
        val gate = StateEventSequencer<Int>()
        gate.offer(0, point(0))
        assertTrue(gate.offer(1, point(1_000, screen = false)).isEmpty())
        assertTrue(gate.offer(2, point(1_100, screen = false, boundary = Boundary.POWER)).isEmpty())
        assertEquals(listOf(1, 3), gate.offer(3, point(1_200, screen = false, boundary = Boundary.SCREEN)).map { it.value })
    }

    @Test fun equalTimestampConfirmationCannotCreateDuplicateRows() {
        val gate = StateEventSequencer<Int>()
        gate.offer(0, point(0)); gate.offer(1, point(1_000, power = PowerState.CHARGING))
        val result = gate.offer(2, point(1_000, power = PowerState.CHARGING, boundary = Boundary.POWER))
        assertEquals(listOf(2), result.map { it.value })
    }

    @Test fun explicitGapAndConflictingRapidTransitionCannotConfirmThePendingInterval() {
        val gate = StateEventSequencer<Unit>()
        gate.offer(Unit, point(0)); gate.offer(Unit, point(1_000, screen = false))
        val result = gate.offer(Unit, point(1_100, boundary = Boundary.GAP))
        assertEquals(listOf(Boundary.GAP, Boundary.GAP), result.map { it.point.boundary })
    }

    @Test fun resetDiscardsPendingDataRatherThanAttachingItToTheNewWindow() {
        val gate = StateEventSequencer<Int>()
        gate.offer(0, point(0)); gate.offer(1, point(1_000, screen = false))
        gate.reset()
        assertEquals(listOf(2), gate.offer(2, point(1_100, generation = "two")).map { it.value })
    }

    @Test fun matchingDozeEventConfirmsOnlyDozeWithoutCreatingScreenOff() {
        val gate = StateEventSequencer<Unit>(); val engine = ObservationEngine()
        listOf(point(0), point(1_000, doze = true), point(1_100, doze = true, boundary = Boundary.DOZE)).forEach { p ->
            gate.offer(Unit, p).forEach { engine.accept(it.point) }
        }
        assertEquals(100L, engine.summary.dozeMs)
        assertEquals(0L, engine.summary.screenOff.durationMs)
    }

    @Test fun lateBroadcastCannotRecoverAnAlreadyUnconfirmedInterval() {
        val gate = StateEventSequencer<Unit>(); val engine = ObservationEngine()
        listOf(point(0), point(1_000, screen = false),
            point(4_000, screen = false, boundary = Boundary.SCREEN)).forEach { p ->
            gate.offer(Unit, p).forEach { engine.accept(it.point) }
        }
        assertEquals(1, engine.summary.gaps)
        assertEquals(0L, engine.summary.screenOn.durationMs)
        assertEquals(3_000L, engine.summary.screenOff.durationMs)
    }

    @Test fun changedGenerationAndBackwardsClockCannotConfirmAPendingEvent() {
        listOf(point(1_100, screen = false, generation = "two", boundary = Boundary.SCREEN),
            point(900, screen = false, boundary = Boundary.SCREEN)).forEach { next ->
            val gate = StateEventSequencer<Unit>(); val engine = ObservationEngine()
            listOf(point(0), point(1_000, screen = false), next).forEach { p ->
                gate.offer(Unit, p).forEach { engine.accept(it.point) }
            }
            assertEquals(2, engine.summary.gaps)
            assertEquals(0L, engine.summary.observedMs)
        }
    }

    @Test fun simultaneousChangesCannotBeConfirmedByOneKindOfEvent() {
        val gate = StateEventSequencer<Unit>(); val engine = ObservationEngine()
        listOf(point(0), point(1_000, screen = false, power = PowerState.CHARGING),
            point(1_100, screen = false, power = PowerState.CHARGING, boundary = Boundary.SCREEN),
            point(4_000, screen = false, power = PowerState.CHARGING)).forEach { p ->
            gate.offer(Unit, p).forEach { engine.accept(it.point) }
        }
        assertEquals(1, engine.summary.gaps)
        assertEquals(0L, engine.summary.screenOff.durationMs)
        assertEquals(3_000L, engine.summary.chargingMs)
    }

    @Test fun unrelatedBatteryBroadcastBeforeWakeConfirmationKeepsTheActualScreenEndpoint() {
        val gate = StateEventSequencer<Int>(); val engine = ObservationEngine()
        listOf(point(0, screen = false), point(1_000, boundary = Boundary.POWER),
            point(1_100, boundary = Boundary.SCREEN)).forEachIndexed { index, p ->
            gate.offer(index, p).forEach { engine.accept(it.point) }
        }
        assertEquals(1_000L, engine.summary.screenOff.durationMs)
        assertEquals(100L, engine.summary.screenOn.durationMs)
        assertEquals(0, engine.summary.gaps)
    }
}
