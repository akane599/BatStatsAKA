package com.akane.voltwise.battery.util

import com.akane.voltwise.battery.util.UpdateGate.Decision
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class UpdateGateTest {
    private fun pushedGate(key: String = "a", at: Long = 1_000) = UpdateGate<String>().apply {
        assertEquals(Decision.Push, decide(key, screenOn = true, nowMs = at))
        pushed(key, at)
    }

    @Test fun firstScreenOnUpdatePushes() {
        assertEquals(Decision.Push, UpdateGate<String>().decide("a", screenOn = true, nowMs = 0))
    }

    @Test fun screenOffNeverPushes() {
        val gate = pushedGate()
        assertEquals(Decision.Skip, gate.decide("b", screenOn = false, nowMs = 60_000))
        assertEquals(Decision.Skip, UpdateGate<String>().decide("a", screenOn = false, nowMs = 0))
    }

    @Test fun unchangedContentIsSkipped() {
        val gate = pushedGate()
        assertEquals(Decision.Skip, gate.decide("a", screenOn = true, nowMs = 1_000 + 60_000))
    }

    @Test fun changedContentWaitsUntilFiveSecondsAfterTheLastPush() {
        val gate = pushedGate()
        assertEquals(Decision.Wait(3_000), gate.decide("b", screenOn = true, nowMs = 3_000))
        assertEquals(Decision.Wait(1), gate.decide("b", screenOn = true, nowMs = 5_999))
        assertEquals(Decision.Push, gate.decide("b", screenOn = true, nowMs = 6_000))
    }

    @Test fun screenOnPushesAtOnceEvenIfUnchangedOrRecent() {
        val gate = pushedGate()
        assertEquals(Decision.Skip, gate.decide("a", screenOn = false, nowMs = 1_500))
        assertEquals(Decision.Push, gate.decide("a", screenOn = true, nowMs = 2_000))
        gate.pushed("a", 2_000)
        assertEquals(Decision.Skip, gate.decide("a", screenOn = true, nowMs = 2_100))
        assertEquals(Decision.Wait(4_900), gate.decide("b", screenOn = true, nowMs = 2_100))
    }

    @Test fun failedPushRetriesTheSameContentAfterTheInterval() {
        val gate = pushedGate()
        gate.pushed(null, 1_000)
        assertEquals(Decision.Wait(4_000), gate.decide("a", screenOn = true, nowMs = 2_000))
        assertEquals(Decision.Push, gate.decide("a", screenOn = true, nowMs = 6_000))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun awaitTurnSuspendsThroughTheWait() = runTest {
        val gate = UpdateGate<String>()
        val clock = { testScheduler.currentTime }
        assertTrue(gate.awaitTurn("a", screenOn = true, clock))
        gate.pushed("a", clock())
        advanceTimeBy(2_000)
        val turn = async { gate.awaitTurn("b", screenOn = true, clock) }
        runCurrent()
        assertFalse(turn.isCompleted)
        advanceTimeBy(3_000)
        runCurrent()
        assertTrue(turn.await())
        assertEquals(5_000, testScheduler.currentTime)
        assertFalse(gate.awaitTurn("c", screenOn = false, clock))
    }
}
