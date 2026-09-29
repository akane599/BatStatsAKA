package app.batstats.battery.shizuku

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class IdleCountdownTest {
    private var unbinds = 0
    private fun TestScope.countdown() = IdleCountdown(backgroundScope, IDLE_MS) { unbinds++ }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test fun unbindsOnceAfterSixtySecondsWithNoCommand() = runTest {
        val idle = countdown()
        idle.begin(); idle.end()
        advance(IDLE_MS - 1)
        assertEquals(0, unbinds)
        advance(1)
        assertEquals(1, unbinds)
        advance(10 * IDLE_MS)
        assertEquals("Idle fires once per idle period", 1, unbinds)
    }

    @Test fun aCommandInFlightNeverCountsAsIdle() = runTest {
        val idle = countdown()
        idle.begin(); idle.begin()
        idle.end()
        advance(10 * IDLE_MS)
        assertEquals(0, unbinds)
        idle.end()
        advance(IDLE_MS)
        assertEquals(1, unbinds)
    }

    @Test fun aNewCommandRestartsTheCountdown() = runTest {
        val idle = countdown()
        idle.begin(); idle.end()
        advance(IDLE_MS - 1_000)
        idle.begin(); idle.end()
        advance(IDLE_MS - 1)
        assertEquals(0, unbinds)
        advance(1)
        assertEquals(1, unbinds)
    }

    @Test fun nothingHappensBeforeTheFirstCommand() = runTest {
        countdown()
        advance(10 * IDLE_MS)
        assertEquals(0, unbinds)
    }

    private companion object {
        const val IDLE_MS = ShizukuBridge.IDLE_UNBIND_MS
    }
}
