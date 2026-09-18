package app.batstats.battery

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.PowerManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
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
        lateinit var ownActivity: BatteryMainActivity
        lateinit var launchIntent: Intent
        scenario.onActivity { ownActivity = it; launchIntent = Intent(it.intent) }
        var phase = "start monitoring"
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
            phase = "first screen-on observation and notification"
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
            phase = "screen off"
            device.sleep()
            withTimeout(120_000) { repo.observation.first { it.latest?.interactive == false } }
            delay(1_500)
            phase = "screen on after wake"
            device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
            withTimeout(120_000) { repo.observation.first { it.latest?.interactive == true && it.screenOff.durationMs > 0 } }
            val screenOff = repo.observation.value.screenOff.durationMs
            phase = "charging transition"
            device.executeShellCommand("dumpsys battery set ac 1")
            device.executeShellCommand("dumpsys battery set status 2")
            withTimeout(120_000) { repo.observation.first { it.chargingMs > 0 } }
            assertEquals(screenOff, repo.observation.value.screenOff.durationMs)
            await { notification()?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)
                ?.contains(MonitoringText(context).state(PowerState.CHARGING)) == true }
            assertEquals(notificationKey, notification()!!.key)
            assertEquals(0L, notification()!!.notification.`when`)
            phase = "notification tap opens observation"
            val title = notification()!!.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
            assertTrue("Notification shade did not open", device.openNotification())
            val row = device.wait(Until.findObject(By.text(title)), 120_000)
            DeviceEnvironment.screenshot("notification-charging-simulated-battery")
            assertNotNull("Monitoring notification is missing from SystemUI", row)
            // Monitoring re-posts this notification as readings change, which replaces the
            // SystemUI row and leaves any previously found node stale, so UiObject2.click()
            // throws StaleObjectException against a row that is present and correct. Re-find
            // the row immediately before each tap and treat only a stale node as a retry. A
            // delivered tap ends the loop, so the screen is never opened twice, and reaching
            // observed drain is still required within the same overall budget.
            val drainTitle = By.text(context.getString(R.string.monitor_drain_title))
            var opened = false
            var attempts = 3
            while (!opened && attempts-- > 0) {
                val target = device.wait(Until.findObject(By.text(title)), 15_000)
                val tapped = try {
                    target?.click(); target != null
                } catch (stale: StaleObjectException) {
                    false // The row was replaced by an update between finding it and tapping.
                }
                if (tapped) opened = device.wait(Until.hasObject(drainTitle), if (attempts > 0) 20_000L else 60_000L)
                if (!opened && attempts > 0) assertTrue("Notification shade did not reopen", device.openNotification())
            }
            DeviceEnvironment.screenshot("notification-opens-observation")
            assertTrue("Tapping the monitoring notification must open observed drain", opened)
            phase = "stop monitoring"
            context.stopService(service)
            withTimeout(120_000) { repo.observation.first { it.stopped } }
            await { notification() == null }
            val count = BatteryGraph.db.batteryDao().count()
            delay(6_500)
            assertEquals("Stopped monitoring must not write another periodic sample", count, BatteryGraph.db.batteryDao().count())
            phase = "restart monitoring with a new window"
            device.executeShellCommand("dumpsys battery unplug")
            device.executeShellCommand("dumpsys battery set status 3")
            context.startForegroundService(service)
            withTimeout(120_000) { repo.observation.first { !it.stopped && it.latest?.power == PowerState.DISCHARGING } }
            assertEquals("Restart starts a new observed window", 0L, repo.observation.value.screenOff.durationMs)
            assertNull(repo.observation.value.screenOff.chargeMah)
        } catch (failure: Throwable) {
            runCatching { DeviceEnvironment.screenshot("monitoring-failure") }
                .exceptionOrNull()?.let(failure::addSuppressed)
            throw AssertionError("Failed during $phase; observation=${repo.observation.value}; " +
                "live=${repo.realtimeFlow.value}; errors=${repo.error.first()}", failure)
        } finally {
            context.stopService(service); repo.stopSampling()
            BatteryGraph.settings.update { settings }
            device.executeShellCommand("dumpsys battery reset")
            device.wakeUp()
            // onNewIntent retains OPEN_DRAIN in production. ActivityScenario matches
            // lifecycle callbacks against its launch intent, including DESTROYED.
            InstrumentationRegistry.getInstrumentation().runOnMainSync { ownActivity.intent = launchIntent }
            scenario.close()
        }
    }
}
