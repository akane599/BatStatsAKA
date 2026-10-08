package com.akane.voltwise.battery.util

import com.akane.voltwise.battery.measurement.AlertReading
import com.akane.voltwise.battery.measurement.BatteryAlert
import com.akane.voltwise.battery.measurement.BatteryAlertSettings
import com.akane.voltwise.battery.measurement.BatteryAlerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifierTest {
    @Test
    fun repeatLowBatteryEpisodeDoesNotUseOnlyAlertOnce() {
        val alerts = BatteryAlerts()
        val settings = BatteryAlertSettings()
        val low = AlertReading(0, 20, 3, 0, -100_000, 250, 30_000)

        assertEquals(setOf(BatteryAlert.LOW), alerts.accept(low, settings))
        assertTrue(alerts.accept(low.copy(elapsedMs = 30_000), settings).isEmpty())
        assertTrue(
            alerts.accept(low.copy(elapsedMs = 60_000, status = 2, plugged = 1), settings).isEmpty(),
        )
        assertEquals(
            setOf(BatteryAlert.LOW),
            alerts.accept(low.copy(elapsedMs = 90_000), settings),
        )
        assertFalse(
            "A new LOW episode must alert even when its stable notification ID is still in the shade",
            Notifier.alertOnlyAlertOnce(),
        )
    }
}
