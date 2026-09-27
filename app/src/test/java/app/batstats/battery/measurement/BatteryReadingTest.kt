package app.batstats.battery.measurement

import org.junit.Assert.*
import org.junit.Test

class BatteryReadingTest {
    @Test fun conflictingCurrentDirectionIsFlaggedWithoutChangingTheReportedValue() {
        assertTrue(BatteryReading.directionConflicts(500, PowerState.DISCHARGING))
        assertTrue(BatteryReading.directionConflicts(-500, PowerState.CHARGING))
        assertFalse(BatteryReading.directionConflicts(-500, PowerState.DISCHARGING))
        assertFalse(BatteryReading.directionConflicts(500, PowerState.CHARGING))
        for (state in PowerState.entries) {
            assertFalse(BatteryReading.directionConflicts(null, state))
            assertFalse(BatteryReading.directionConflicts(0, state))
        }
        assertEquals(500L, BatteryReading.currentUa(500))
    }
    @Test fun missingAndInvalidLevelAreNotEmptyBattery() {
        assertNull(BatteryReading.percentage(-1, 100))
        assertNull(BatteryReading.percentage(10, 0))
        assertNull(BatteryReading.percentage(101, 100))
        assertEquals(50, BatteryReading.percentage(1, 2))
        assertEquals(0, BatteryReading.percentage(0, 100))
        assertEquals(50, BatteryReading.percentage(Int.MAX_VALUE / 2, Int.MAX_VALUE))
    }
    @Test fun currentKeepsZeroSignAndSmallValuesWithoutGuessingUnits() {
        assertNull(BatteryReading.currentUa(Long.MIN_VALUE))
        assertNull(BatteryReading.currentUa(Int.MIN_VALUE.toLong()))
        assertEquals(0L, BatteryReading.currentUa(0))
        assertEquals(-900L, BatteryReading.currentUa(-900))
        assertEquals(900L, BatteryReading.currentUa(900))
        assertEquals(-2000.0, BatteryReading.powerMw(-500_000, 4000)!!, 0.001)
        assertNull(BatteryReading.powerMw(null, 4000))
    }
    @Test fun validationPreservesUnavailableAndPhysicallyPossibleZeroTemperature() {
        assertNull(BatteryReading.chargeUah(Long.MIN_VALUE))
        assertNull(BatteryReading.chargeUah(-1))
        assertEquals(0L, BatteryReading.chargeUah(0))
        assertNull(BatteryReading.voltageMv(0))
        assertEquals(0, BatteryReading.temperatureDeciC(0))
        assertNull(BatteryReading.temperatureDeciC(Int.MIN_VALUE))
    }
    @Test fun pluggedFullIsNotCharging() {
        assertEquals(PowerState.PLUGGED, BatteryReading.powerState(5, 1))
        assertEquals(PowerState.CHARGING, BatteryReading.powerState(2, 1))
        assertEquals(PowerState.DISCHARGING, BatteryReading.powerState(3, 0))
        assertEquals(PowerState.UNKNOWN, BatteryReading.powerState(1, null))
    }
    @Test fun counterResetsAndJumpsAreMissingNotZeroConsumption() {
        assertNull(BatteryReading.dischargedUah(100, 200, 1000))
        assertNull(BatteryReading.dischargedUah(1_000_000, 0, 1000))
        assertNull(BatteryReading.dischargedUah(null, 0, 1000))
        assertEquals(0L, BatteryReading.dischargedUah(100, 100, 1000))
    }
}
