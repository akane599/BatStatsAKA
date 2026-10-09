package com.akane.voltwise.viewmodel

import com.akane.voltwise.battery.data.db.BatterySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionDetailsCapacityTest {
    private fun sample(counter: Long?, level: Int = 70) = BatterySample(
        timestamp = 1_000, levelPercent = level, status = 3, plugged = 0, currentNowUa = -500_000,
        chargeCounterUah = counter, voltageMv = 3_900, temperatureDeciC = 300, health = 2, screenOn = true,
    )

    @Test fun newestUsableCounterWinsEvenWhenLatestReadingCannotSupplyCapacity() {
        val readings = listOf(sample(2_800_000), sample(3_500_000), sample(10_000), sample(400_000, level = 9))
        assertEquals(5_000_000L, SessionDetailsMapping.counterFullUah(readings, storedEstimateUah = 6_000_000))
        assertEquals(6_000_000L, SessionDetailsMapping.counterFullUah(readings.takeLast(2), storedEstimateUah = 6_000_000))
        assertNull(SessionDetailsMapping.counterFullUah(readings.takeLast(2)))
        assertEquals(6_000_000L, SessionDetailsMapping.counterFullUah(emptyList(), storedEstimateUah = 6_000_000))
    }
}
