package app.batstats.battery.drain

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.batstats.battery.BatteryGraph
import app.batstats.battery.BatteryMainActivity
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.measurement.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonitoringNotificationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun richNotificationKeepsSharedWindowValuesSilentStableAndIndependentFromWidgetIntent() {
        val engine = ObservationEngine()
        fun point(t: Long) = Observation(1_000_000 + t, t, t, 80, 4_000_000 - t / 60, -60_000,
            4000, PowerState.DISCHARGING, true, false, "test")
        engine.accept(point(0))
        val summary = engine.accept(point(60_000))
        val sample = BatterySample(timestamp = 1_060_000, levelPercent = 80, status = 3, plugged = 0,
            currentNowUa = -60_000, chargeCounterUah = 3_999_000, voltageMv = 4000, temperatureDeciC = 250,
            health = 2, screenOn = true)
        val manager = DrainNotificationManager(context, BatteryGraph.repo)
        val notification = manager.getNotification(BatteryRepository.Realtime(sample), summary, "Test source", null)
        assertEquals(0L, notification.`when`)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(DrainNotificationManager.CHANNEL_ID, notification.channelId)
        assertEquals(1, notification.actions.size)
        val expanded = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(expanded.contains(MonitoringText.bucket(summary.screenOn)))
        assertTrue(expanded.contains(MonitoringText.since(summary)))
        assertTrue(expanded.contains(MonitoringText.cpuSuspend(summary)))
        assertNull(summary.screenOff.rateMa)
        val widgetIntent = PendingIntent.getActivity(context, 0, Intent(context, BatteryMainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        assertNotEquals(widgetIntent, notification.contentIntent)
        val failed = manager.getNotification(BatteryRepository.Realtime(sample), summary, "Test source", "Collection interrupted")
        assertEquals(0L, failed.`when`)
        assertEquals("Collection interrupted", failed.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }
}
