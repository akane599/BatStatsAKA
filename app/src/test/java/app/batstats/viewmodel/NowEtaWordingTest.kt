package app.batstats.viewmodel

import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.measurement.EtaHold
import app.batstats.battery.measurement.PowerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [NowMapping.etaPending] / [NowMapping.hero]: "time left" only on battery, "time to full" only while charging. */
class NowEtaWordingTest {
    private fun reading(status: Int, plugged: Int, etaMs: Long? = null) = EtaHold.next(
        EtaHold.Reading(),
        BatteryRepository.Realtime(
            BatterySample(timestamp = 1_000_000, levelPercent = 100, status = status, plugged = plugged, currentNowUa = 0,
                chargeCounterUah = null, voltageMv = 4_300, temperatureDeciC = 300, health = 2, screenOn = true, etaMs = etaMs),
        ),
    )

    @Test fun onBatteryItIsTimeLeft() {
        assertEquals(EtaPending.ESTIMATING_LEFT, NowMapping.etaPending(PowerState.DISCHARGING, monitoring = true))
        assertEquals(EtaPending.NEEDS_MONITORING_LEFT, NowMapping.etaPending(PowerState.DISCHARGING, monitoring = false))
    }

    @Test fun chargingWithoutAndroidsEstimateIsTimeToFull() {
        assertEquals(EtaPending.ESTIMATING_FULL, NowMapping.etaPending(PowerState.CHARGING, monitoring = true))
        assertEquals(EtaPending.NEEDS_MONITORING_FULL, NowMapping.etaPending(PowerState.CHARGING, monitoring = false))
    }

    @Test fun pluggedButNotChargingSaysNothing() {
        assertNull(NowMapping.etaPending(PowerState.PLUGGED, monitoring = true))
        assertNull(NowMapping.etaPending(PowerState.UNKNOWN, monitoring = true))
        // Full at 100 %: status FULL (5), plugged. No "Estimating time left" forever.
        assertNull(NowMapping.hero(reading(status = 5, plugged = 1), monitoring = true, startBlocked = false).etaPending)
    }

    @Test fun anEstimateReplacesTheLineAndNoReadingSaysNothing() {
        val hero = NowMapping.hero(reading(status = 2, plugged = 1, etaMs = 1_800_000), monitoring = false, startBlocked = false)
        assertEquals(1_800_000L, hero.eta?.remainingMs)
        assertNull(hero.etaPending)
        assertNull(NowMapping.hero(EtaHold.Reading(), monitoring = true, startBlocked = false).etaPending)
    }
}
