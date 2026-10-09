package com.akane.voltwise.battery.measurement

import com.akane.voltwise.battery.data.BatteryRepository
import com.akane.voltwise.battery.data.db.BatterySample
import org.junit.Assert.*
import org.junit.Test

class EtaHoldTest {
    private fun capture(
        timestamp: Long,
        etaMs: Long? = null,
        status: Int = 3,
        plugged: Int = 0,
        screenOn: Boolean = true,
        expectedIntervalMs: Long = SamplingPolicy.SCREEN_ON_INTERVAL_MS,
    ) = BatteryRepository.Realtime(
        BatterySample(timestamp = timestamp, levelPercent = 60, status = status, plugged = plugged,
            currentNowUa = -500_000, chargeCounterUah = null, voltageMv = 3_900, temperatureDeciC = 300, health = 2,
            screenOn = screenOn, etaMs = etaMs),
        expectedIntervalMs = expectedIntervalMs,
    )

    @Test fun writersEstimateIsHeldAndCountedDownAcrossCapturesWithoutOne() {
        val estimated = EtaHold.next(EtaHold.Reading(), capture(1_000_000, etaMs = 3_600_000))
        assertEquals(3_600_000L, estimated.remainingMs)
        val raw = EtaHold.next(estimated, capture(1_030_000))
        assertEquals(3_570_000L, raw.remainingMs)
        val newer = EtaHold.next(raw, capture(1_031_000, etaMs = 3_500_000))
        assertEquals(3_500_000L, newer.remainingMs)
    }

    @Test fun screenOffEstimateSurvivesTheNextPollAndFasterScreenOnCaptures() {
        val estimated = EtaHold.next(EtaHold.Reading(), capture(1_000_000, etaMs = 3_600_000,
            screenOn = false, expectedIntervalMs = SamplingPolicy.SCREEN_OFF_INTERVAL_MS))
        val raw = EtaHold.next(estimated, capture(1_300_000,
            screenOn = false, expectedIntervalMs = SamplingPolicy.SCREEN_OFF_INTERVAL_MS))
        assertEquals("The 300 s raw poll counts down the writer's estimate", 3_300_000L, raw.remainingMs)
        val awake = EtaHold.next(raw, capture(1_310_000))
        assertEquals(3_290_000L, awake.remainingMs)
        val demand = EtaHold.next(awake, capture(1_312_000, expectedIntervalMs = SamplingPolicy.REALTIME_INTERVAL_MS))
        assertEquals("A shorter cadence must not expire the estimate before the writer copy", 3_288_000L, demand.remainingMs)
    }

    @Test fun firstScreenOnCaptureAfterFiveMinutesKeepsTheScreenOffEstimate() {
        val estimated = EtaHold.next(EtaHold.Reading(), capture(1_000_000, etaMs = 3_600_000,
            screenOn = false, expectedIntervalMs = SamplingPolicy.SCREEN_OFF_INTERVAL_MS))
        assertEquals(3_300_000L, EtaHold.next(estimated, capture(1_300_000)).remainingMs)
    }

    @Test fun screenOffCaptureExtendsTheBoundWithoutRestartingTheEstimateClock() {
        val estimated = EtaHold.next(EtaHold.Reading(), capture(1_000_000, etaMs = 3_600_000))
        val off = EtaHold.next(estimated, capture(1_001_000,
            screenOn = false, expectedIntervalMs = SamplingPolicy.SCREEN_OFF_INTERVAL_MS))
        val awake = EtaHold.next(off, capture(1_301_000))
        assertEquals(3_299_000L, awake.remainingMs)
        assertNull("The longer bound still expires from the original estimate", EtaHold.next(awake, capture(1_600_001)).remainingMs)
    }

    @Test fun holdEndsAtItsCadenceBoundOrWhenThePowerStateChanges() {
        for (interval in listOf(SamplingPolicy.REALTIME_INTERVAL_MS, SamplingPolicy.SCREEN_ON_INTERVAL_MS,
            SamplingPolicy.SCREEN_OFF_INTERVAL_MS)) {
            val bound = maxOf(EtaHold.HOLD_MS, 2 * interval)
            val estimated = EtaHold.next(EtaHold.Reading(), capture(1_000_000, etaMs = 3_600_000,
                expectedIntervalMs = interval))
            assertEquals(3_600_000L - bound, EtaHold.next(estimated, capture(1_000_000 + bound)).remainingMs)
            assertNull(EtaHold.next(estimated, capture(1_000_001 + bound)).remainingMs)
            assertNull("Charging drops a discharge estimate", EtaHold.next(estimated, capture(1_010_000, status = 2, plugged = 1)).remainingMs)
        }
    }

    @Test fun newScreenOnEstimateResetsTheScreenOffHoldBound() {
        val estimated = EtaHold.next(EtaHold.Reading(), capture(1_000_000, etaMs = 3_600_000,
            screenOn = false, expectedIntervalMs = SamplingPolicy.SCREEN_OFF_INTERVAL_MS))
        val newer = EtaHold.next(estimated, capture(1_300_000, etaMs = 3_000_000))
        assertEquals(3_000_000L, newer.remainingMs)
        assertEquals(2_940_000L, EtaHold.next(newer, capture(1_360_000)).remainingMs)
        assertNull("A fresh fast-cadence estimate must not inherit the old longer bound",
            EtaHold.next(newer, capture(1_360_001)).remainingMs)
    }

    @Test fun noReadingKeepsTheHeldEstimateButShowsNone() {
        val estimated = EtaHold.next(EtaHold.Reading(), capture(1_000_000, etaMs = 3_600_000))
        val empty = EtaHold.next(estimated, BatteryRepository.Realtime())
        assertNull(empty.remainingMs)
        assertEquals(estimated.held, empty.held)
        assertNull("A non-positive estimate is none", EtaHold.next(EtaHold.Reading(), capture(0, etaMs = 0)).remainingMs)
    }
}
