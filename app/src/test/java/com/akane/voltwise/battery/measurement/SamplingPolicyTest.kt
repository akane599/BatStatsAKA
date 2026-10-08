package com.akane.voltwise.battery.measurement

import org.junit.Assert.*
import org.junit.Test

class SamplingPolicyTest {
    /** A discharging capture stamped by the policy; the counter drops 10 µAh per elapsed second. */
    private fun capture(elapsedMs: Long, uptimeMs: Long, screenOn: Boolean, demandHeld: Boolean,
                        boundary: Boundary = Boundary.SAMPLE) = SamplingPolicy.stamp(
        Observation(1_000_000 + elapsedMs, elapsedMs, uptimeMs, 80, 4_000_000 - elapsedMs / 100, -36_000, 4000,
            PowerState.DISCHARGING, screenOn, false, "one", boundary = boundary),
        demandHeld,
    )

    @Test fun intervalsFollowDemandThenScreenState() {
        assertEquals(2_000L, SamplingPolicy.expectedIntervalMs(demandHeld = true, screenOn = true))
        assertEquals(2_000L, SamplingPolicy.expectedIntervalMs(demandHeld = true, screenOn = false))
        assertEquals(30_000L, SamplingPolicy.expectedIntervalMs(demandHeld = false, screenOn = true))
        assertEquals(300_000L, SamplingPolicy.expectedIntervalMs(demandHeld = false, screenOn = false))
    }

    @Test fun monitoringOffPollsOnlyWhileDemandIsHeld() {
        assertNull(SamplingPolicy.pollIntervalMs(monitoring = false, demandHeld = false, screenOn = true))
        assertEquals(2_000L, SamplingPolicy.pollIntervalMs(monitoring = false, demandHeld = true, screenOn = true))
        assertEquals(30_000L, SamplingPolicy.pollIntervalMs(monitoring = true, demandHeld = false, screenOn = true))
        assertEquals(300_000L, SamplingPolicy.pollIntervalMs(monitoring = true, demandHeld = false, screenOn = false))
    }

    @Test fun changedIntervalCallsForAHandoffCapture() {
        assertTrue(SamplingPolicy.needsHandoffCapture(scheduledIntervalMs = 2_000, newIntervalMs = 30_000))
        assertTrue(SamplingPolicy.needsHandoffCapture(scheduledIntervalMs = 300_000, newIntervalMs = 2_000))
        assertTrue(SamplingPolicy.needsHandoffCapture(scheduledIntervalMs = null, newIntervalMs = 30_000))
        assertFalse(SamplingPolicy.needsHandoffCapture(scheduledIntervalMs = 30_000, newIntervalMs = 30_000))
        assertFalse(SamplingPolicy.needsHandoffCapture(scheduledIntervalMs = 2_000, newIntervalMs = null))
    }

    @Test fun stampUsesTheStateAtCaptureTime() {
        assertEquals(2_000L, capture(0, 0, screenOn = false, demandHeld = true).expectedIntervalMs)
        assertEquals(300_000L, capture(0, 0, screenOn = false, demandHeld = false).expectedIntervalMs)
        assertEquals(30_000L, capture(0, 0, screenOn = true, demandHeld = false).expectedIntervalMs)
    }

    @Test fun twoToThirtyToThreeHundredSecondHandoffsLeaveNoGap() {
        val engine = ObservationEngine()
        for (t in 0L..20_000L step 2_000) engine.accept(capture(t, t, screenOn = true, demandHeld = true))
        // Demand released at 21 s: the handoff capture carries 30 s.
        assertTrue(SamplingPolicy.needsHandoffCapture(2_000, SamplingPolicy.pollIntervalMs(true, false, true)))
        engine.accept(capture(21_000, 21_000, screenOn = true, demandHeld = false))
        for (t in listOf(51_000L, 81_000L, 111_000L)) engine.accept(capture(t, t, screenOn = true, demandHeld = false))
        // Screen off at 120 s: the SCREEN capture carries 300 s, so a 300 s awake wait is expected.
        val off = capture(120_000, 120_000, screenOn = false, demandHeld = false, boundary = Boundary.SCREEN)
        assertEquals(300_000L, off.expectedIntervalMs)
        engine.accept(off)
        // Next poll: 300 s of uptime later, after an hour of deep sleep.
        engine.accept(capture(3_720_000, 420_000, screenOn = false, demandHeld = false))
        val result = engine.accept(capture(4_020_000, 720_000, screenOn = false, demandHeld = false))
        assertEquals(0, result.gaps)
        assertNull(result.lastIssue)
        assertEquals(120_000L, result.screenOn.durationMs)
        assertEquals(3_900_000L, result.screenOff.durationMs)
        assertEquals(3_300_000L, result.cpuSuspendMs)
    }

    @Test fun screenOffCaptureKeepingTheOldIntervalWouldBeAGap() {
        val engine = ObservationEngine()
        engine.accept(capture(0, 0, screenOn = true, demandHeld = false))
        val stale = capture(30_000, 30_000, screenOn = false, demandHeld = false, boundary = Boundary.SCREEN)
            .copy(expectedIntervalMs = 30_000)
        engine.accept(stale)
        val result = engine.accept(capture(3_630_000, 330_000, screenOn = false, demandHeld = false))
        assertEquals(1, result.gaps)
        assertEquals("Gap in observation", result.lastIssue)
    }

    @Test fun demandReleasedAfterScreenOffHandsOffBeforeTheLongWait() {
        fun run(handoff: Boolean): ObservationSummary {
            val engine = ObservationEngine()
            for (t in 0L..10_000L step 2_000) engine.accept(capture(t, t, screenOn = true, demandHeld = true))
            // The SCREEN_OFF broadcast lands while the Now screen still holds demand.
            val off = capture(11_000, 11_000, screenOn = false, demandHeld = true, boundary = Boundary.SCREEN)
            assertEquals(2_000L, off.expectedIntervalMs)
            engine.accept(off)
            val next = SamplingPolicy.pollIntervalMs(monitoring = true, demandHeld = false, screenOn = false)
            assertTrue(SamplingPolicy.needsHandoffCapture(off.expectedIntervalMs, next))
            if (handoff) engine.accept(capture(11_500, 11_500, screenOn = false, demandHeld = false))
            return engine.accept(capture(1_811_500, 311_500, screenOn = false, demandHeld = false))
        }
        assertEquals(0, run(handoff = true).gaps)
        assertEquals(1, run(handoff = false).gaps)
    }

    @Test fun screenOnAndDemandShortenTheIntervalWithoutGaps() {
        val engine = ObservationEngine()
        engine.accept(capture(0, 0, screenOn = false, demandHeld = false))
        engine.accept(capture(1_200_000, 300_000, screenOn = false, demandHeld = false))
        val on = capture(1_260_000, 360_000, screenOn = true, demandHeld = false, boundary = Boundary.SCREEN)
        assertEquals(30_000L, on.expectedIntervalMs)
        engine.accept(on)
        assertTrue(SamplingPolicy.needsHandoffCapture(30_000, SamplingPolicy.pollIntervalMs(true, true, true)))
        val acquired = capture(1_265_000, 365_000, screenOn = true, demandHeld = true)
        assertEquals(2_000L, acquired.expectedIntervalMs)
        engine.accept(acquired)
        var result = engine.summary
        for (i in 1..5) result = engine.accept(capture(1_265_000L + i * 2_000, 365_000L + i * 2_000, screenOn = true, demandHeld = true))
        assertEquals(0, result.gaps)
        assertEquals(1_260_000L, result.screenOff.durationMs)
        assertEquals(15_000L, result.screenOn.durationMs)
    }
}
