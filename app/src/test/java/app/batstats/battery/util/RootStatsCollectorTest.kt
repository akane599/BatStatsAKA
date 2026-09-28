package app.batstats.battery.util

import org.junit.Assert.*
import org.junit.Test

class RootStatsCollectorTest {
    @Test fun designCapacityIsParsedFromABatteryUeventInAbiUnits() {
        val raw = "POWER_SUPPLY_TYPE=Battery\nPOWER_SUPPLY_CHARGE_FULL_DESIGN=4000000\nPOWER_SUPPLY_CHARGE_FULL=3600000"
        assertEquals(4_000_000L, RootStatsCollector.parseChargeFullDesignUah(raw))
    }

    @Test fun missingOrInvalidFieldsAreNotGuessed() {
        assertNull("Not a battery uevent", RootStatsCollector.parseChargeFullDesignUah("POWER_SUPPLY_TYPE=Mains\nPOWER_SUPPLY_CHARGE_FULL_DESIGN=4000000"))
        assertNull("No CHARGE_FULL_DESIGN field", RootStatsCollector.parseChargeFullDesignUah("POWER_SUPPLY_TYPE=Battery\nPOWER_SUPPLY_CHARGE_FULL=3600000"))
        assertNull("Out of the plausible ABI range", RootStatsCollector.parseChargeFullDesignUah("POWER_SUPPLY_TYPE=Battery\nPOWER_SUPPLY_CHARGE_FULL_DESIGN=0"))
        assertNull("A permission failure is not a value", RootStatsCollector.parseChargeFullDesignUah("Permission denied"))
    }
}
