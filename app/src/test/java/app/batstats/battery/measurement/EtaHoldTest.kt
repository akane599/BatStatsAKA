package app.batstats.battery.measurement

import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.BatterySample
import org.junit.Assert.*
import org.junit.Test

class EtaHoldTest {
    private fun capture(timestamp: Long, etaMs: Long? = null, status: Int = 3, plugged: Int = 0) =
        BatteryRepository.Realtime(BatterySample(timestamp = timestamp, levelPercent = 60, status = status, plugged = plugged,
            currentNowUa = -500_000, chargeCounterUah = null, voltageMv = 3_900, temperatureDeciC = 300, health = 2,
            screenOn = true, etaMs = etaMs))

    @Test fun writersEstimateIsHeldAndCountedDownAcrossCapturesWithoutOne() {
        val estimated = EtaHold.next(EtaHold.Reading(), capture(1_000_000, etaMs = 3_600_000))
        assertEquals(3_600_000L, estimated.remainingMs)
        val raw = EtaHold.next(estimated, capture(1_030_000))
        assertEquals(3_570_000L, raw.remainingMs)
        val newer = EtaHold.next(raw, capture(1_031_000, etaMs = 3_500_000))
        assertEquals(3_500_000L, newer.remainingMs)
    }

    @Test fun holdEndsAfterAMinuteOrWhenThePowerStateChanges() {
        val estimated = EtaHold.next(EtaHold.Reading(), capture(1_000_000, etaMs = 3_600_000))
        assertEquals(3_540_000L, EtaHold.next(estimated, capture(1_000_000 + EtaHold.HOLD_MS)).remainingMs)
        assertNull(EtaHold.next(estimated, capture(1_000_001 + EtaHold.HOLD_MS)).remainingMs)
        assertNull("Charging drops a discharge estimate", EtaHold.next(estimated, capture(1_010_000, status = 2, plugged = 1)).remainingMs)
    }

    @Test fun noReadingKeepsTheHeldEstimateButShowsNone() {
        val estimated = EtaHold.next(EtaHold.Reading(), capture(1_000_000, etaMs = 3_600_000))
        val empty = EtaHold.next(estimated, BatteryRepository.Realtime())
        assertNull(empty.remainingMs)
        assertEquals(estimated.held, empty.held)
        assertNull("A non-positive estimate is none", EtaHold.next(EtaHold.Reading(), capture(0, etaMs = 0)).remainingMs)
    }
}
