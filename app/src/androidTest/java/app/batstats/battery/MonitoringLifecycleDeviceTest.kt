package app.batstats.battery

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.PowerManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.batstats.R
import app.batstats.battery.drain.DrainNotificationManager
import app.batstats.battery.drain.MonitoringText
import app.batstats.battery.measurement.PowerState
import app.batstats.battery.service.BatteryMonitorService
import app.batstats.test.DeviceEnvironment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonitoringLifecycleDeviceTest {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val context get() = DeviceEnvironment.context
    private val device get() = DeviceEnvironment.device
    private val repo get() = BatteryGraph.repo
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private fun notification() = manager.activeNotifications.singleOrNull { it.id == DrainNotificationManager.NOTIFICATION_ID }
    private suspend fun await(condition: () -> Boolean) = withTimeout(120_000) { while (!condition()) delay(100) }

    @Test fun injectedBatteryTransitionsAndRealScreenEventsDoNotInventOffTimeOrKeepRecordingAfterStop() = runBlocking {
        DeviceEnvironment.requireDisposableEmulator()
        val settings = BatteryGraph.settings.flow.first()
        val service = Intent(context, BatteryMonitorService::class.java)
        val scenario = ActivityScenario.launch(BatteryMainActivity::class.java)
        try {
            context.stopService(service); repo.stopSampling()
            await { !repo.isMonitoringFlow.value }
            device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
            await { context.getSystemService(PowerManager::class.java).isInteractive }
            device.executeShellCommand("dumpsys battery unplug")
            device.executeShellCommand("dumpsys battery set status 3")
            device.executeShellCommand("dumpsys battery set level 80")
            BatteryGraph.settings.update { it.copy(monitoringIntervalIndex = 0) }
            scenario.onActivity { it.startForegroundService(service) }
            withTimeout(120_000) { repo.observation.first { it.screenOn.durationMs > 0 } }
            assertEquals(PowerState.DISCHARGING, repo.realtimeFlow.value.powerState)
            assertEquals(0L, repo.observation.value.screenOff.durationMs)
            assertNull(repo.observation.value.screenOff.chargeMah)
            await { notification()?.notification?.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?.contains(context.getString(R.string.monitor_screen_off, MonitoringText(context).bucket(repo.observation.value.screenOff)) + "\n" + context.getString(R.string.monitor_no_period)) == true }
            assertEquals(0L, notification()!!.notification.`when`)
            assertTrue(notification()!!.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            val notificationKey = notification()!!.key
            DeviceEnvironment.screenshot("monitoring-screen-on-simulated-battery")
            device.sleep()
            withTimeout(120_000) { repo.observation.first { it.latest?.interactive == false } }
            delay(1_500)
            device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
            withTimeout(120_000) { repo.observation.first { it.latest?.interactive == true && it.screenOff.durationMs > 0 } }
            val screenOff = repo.observation.value.screenOff.durationMs
            device.executeShellCommand("dumpsys battery set ac 1")
            device.executeShellCommand("dumpsys battery set status 2")
            withTimeout(120_000) { repo.observation.first { it.chargingMs > 0 } }
            assertEquals(screenOff, repo.observation.value.screenOff.durationMs)
            await { notification()?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)
                ?.contains(MonitoringText(context).state(PowerState.CHARGING)) == true }
            assertEquals(notificationKey, notification()!!.key)
            assertEquals(0L, notification()!!.notification.`when`)
            device.openNotification(); DeviceEnvironment.screenshot("notification-charging-simulated-battery"); device.pressBack()
            // PendingIntent must open the observed-drain destination even after widget intent creation.
            notification()!!.notification.contentIntent.send()
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text(context.getString(R.string.monitor_drain_title))), 120_000))
            DeviceEnvironment.screenshot("notification-opens-observation")
            context.stopService(service)
            withTimeout(120_000) { repo.observation.first { it.stopped } }
            await { notification() == null }
            val count = BatteryGraph.db.batteryDao().count()
            delay(6_500)
            assertEquals("Stopped monitoring must not write another periodic sample", count, BatteryGraph.db.batteryDao().count())
            device.executeShellCommand("dumpsys battery unplug")
            device.executeShellCommand("dumpsys battery set status 3")
            scenario.onActivity { it.startForegroundService(service) }
            withTimeout(120_000) { repo.observation.first { !it.stopped && it.latest?.power == PowerState.DISCHARGING } }
            assertEquals("Restart starts a new observed window", 0L, repo.observation.value.screenOff.durationMs)
            assertNull(repo.observation.value.screenOff.chargeMah)
        } finally {
            context.stopService(service); repo.stopSampling()
            BatteryGraph.settings.update { settings }
            device.executeShellCommand("dumpsys battery reset")
            device.wakeUp()
            scenario.close()
        }
    }
}
