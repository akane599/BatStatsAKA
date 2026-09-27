package app.batstats.battery.util

import org.junit.Assert.*
import org.junit.Test

class KernelStatsTest {
    @Test fun capacityUsesAbiUnitsAndNeverCycleBasedHealth() {
        val raw = "POWER_SUPPLY_TYPE=Battery\nPOWER_SUPPLY_CHARGE_FULL_DESIGN=4000000\nPOWER_SUPPLY_CHARGE_FULL=3600000\nPOWER_SUPPLY_CURRENT_NOW=-1000\nPOWER_SUPPLY_CYCLE_COUNT=900\nPOWER_SUPPLY_CHARGE_NOW=0"
        val b = KernelStats.battery(raw, 123)!!
        assertEquals(4000000L, b.chargeFullDesign)
        assertEquals(-1000L, b.currentNow)
        assertEquals(0L, b.chargeNow)
        assertEquals(90.0, b.batteryAge!!, 0.0)
        assertNull(KernelStats.battery("POWER_SUPPLY_TYPE=Battery\nPOWER_SUPPLY_CYCLE_COUNT=900", 0)!!.batteryAge)
    }
    @Test fun missingAndInvalidFieldsAreNotGuessed() {
        val b = KernelStats.battery("POWER_SUPPLY_TYPE=Battery\nPOWER_SUPPLY_CHARGE_FULL=-1\nPOWER_SUPPLY_CHARGE_FULL_DESIGN=0\nPOWER_SUPPLY_CURRENT_NOW=99999999999", 1)!!
        assertNull(b.batteryAge); assertNull(b.currentNow); assertNull(b.cycleCount); assertNull(b.tempNow)
        assertNull(KernelStats.battery("Permission denied", 1))
    }
    @Test fun modernWakeSourcesAndLegacyWakelocksHaveDifferentUnits() {
        val modern = KernelStats.wakelocks("source=/sys/kernel/debug/wakeup_sources\nname active_count event_count wakeup_count expire_count active_since total_time max_time last_change prevent_suspend_time\nradio 1 4 3 0 0 1200 800 0 1200").single()
        val legacy = KernelStats.wakelocks("source=/proc/wakelocks\nname count expire_count wake_count active_since total_time sleep_time max_time last_change\nradio 4 0 3 0 1200000000 0 800000000 0").single()
        assertEquals(1200L, modern.totalTimeMs); assertEquals(1200L, legacy.totalTimeMs)
        assertEquals(800L, modern.maxTimeMs); assertEquals(4L, legacy.count)
        assertTrue(KernelStats.wakelocks("source=/sys/kernel/wakelock_stats\nunknown format").isEmpty())
    }
    @Test fun cpuMissingFrequencyAndThermalSensorAreUnknown() {
        val c = KernelStats.cpu("policy=policy2\nscaling_max_freq=2200000\nstate=100000 7\nstate=200000 -1").single()
        assertNull(c.currentFreq); assertEquals(mapOf(100000L to 7L), c.timeInState)
        val t = KernelStats.thermal("zone=thermal_zone0\ntype=battery\ntemp=-274000\ntrip_point_0_type=passive\ntrip_point_0_temp=45000").single()
        assertNull(t.tempMilliC); assertEquals(45000, t.tripPoints.single().tempMilliC)
    }
}
