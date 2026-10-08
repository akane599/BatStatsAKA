package com.akane.voltwise.battery.widget

import com.akane.voltwise.battery.measurement.PowerState
import com.akane.voltwise.battery.widget.WidgetUpdater.Direction
import org.junit.Assert.assertEquals
import org.junit.Test

/** [WidgetUpdater.directionFor]: the widget icon's energy-direction tint, mirrors NowHero.directionColor. */
class WidgetDirectionTest {
    @Test fun chargingIsCharge() {
        assertEquals(Direction.CHARGE, WidgetUpdater.directionFor(PowerState.CHARGING, level = 50))
    }

    @Test fun pluggedButNotChargingIsStillCharge() {
        assertEquals(Direction.CHARGE, WidgetUpdater.directionFor(PowerState.PLUGGED, level = 100))
    }

    @Test fun dischargingAboveTheLowThresholdIsDrain() {
        assertEquals(Direction.DRAIN, WidgetUpdater.directionFor(PowerState.DISCHARGING, level = 50))
    }

    @Test fun dischargingWithNoLevelReadingIsDrainNotHeat() {
        assertEquals(Direction.DRAIN, WidgetUpdater.directionFor(PowerState.DISCHARGING, level = null))
    }

    @Test fun dischargingAtOrBelowTheLowThresholdIsHeat() {
        assertEquals(Direction.HEAT, WidgetUpdater.directionFor(PowerState.DISCHARGING, level = WidgetUpdater.LOW_BATTERY_LEVEL))
        assertEquals(Direction.HEAT, WidgetUpdater.directionFor(PowerState.DISCHARGING, level = WidgetUpdater.LOW_BATTERY_LEVEL - 1))
    }

    @Test fun dischargingJustAboveTheLowThresholdIsStillDrain() {
        assertEquals(Direction.DRAIN, WidgetUpdater.directionFor(PowerState.DISCHARGING, level = WidgetUpdater.LOW_BATTERY_LEVEL + 1))
    }

    @Test fun unknownPowerStateIsNeutral() {
        assertEquals(Direction.NEUTRAL, WidgetUpdater.directionFor(PowerState.UNKNOWN, level = 50))
        assertEquals(Direction.NEUTRAL, WidgetUpdater.directionFor(PowerState.UNKNOWN, level = null))
    }
}
