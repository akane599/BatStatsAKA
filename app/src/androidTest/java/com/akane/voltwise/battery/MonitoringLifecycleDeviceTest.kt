package com.akane.voltwise.battery

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
import androidx.test.uiautomator.Until
import com.akane.voltwise.R
import com.akane.voltwise.battery.drain.DrainNotificationManager
import com.akane.voltwise.battery.drain.NO_VALUE
import com.akane.voltwise.battery.measurement.PowerState
import com.akane.voltwise.battery.service.BatteryMonitorService
import com.akane.voltwise.battery.service.SamplingDemand
import com.akane.voltwise.test.DeviceEnvironment
import com.akane.voltwise.ui.TestTags
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.regex.Pattern

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
        val service = Intent(context, BatteryMonitorService::class.java)
        // Held like the Now screen holds it: 2 s polls, so each step needs no 30 s/300 s wait.
        val demand = GlobalContext.get().get<SamplingDemand>().acquire("MonitoringLifecycleDeviceTest")
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
            scenario.onActivity { it.startForegroundService(service) }
            phase = "first screen-on observation and notification"
            withTimeout(120_000) { repo.observation.first { it.screenOn.durationMs > 0 } }
            assertEquals(PowerState.DISCHARGING, repo.realtimeFlow.value.powerState)
            assertEquals(0L, repo.observation.value.screenOff.durationMs)
            assertNull(repo.observation.value.screenOff.chargeMah)
            // Line 2 ("On … · Off …"): no screen-off time yet, so no screen-off drain is invented.
            val noOffDrain = context.getString(R.string.notification_summary_drain, "\u0000", NO_VALUE).substringAfter("\u0000")
            await { notification()?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.contains(noOffDrain) == true &&
                notification()?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)
                    ?.contains(context.getString(R.string.notification_state_discharging)) == true }
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
            val charging = context.getString(R.string.notification_state_charging)
            await { notification()?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.contains(charging) == true }
            assertEquals(notificationKey, notification()!!.key)
            assertEquals(0L, notification()!!.notification.`when`)
            phase = "notification tap opens Now"
            assertTrue("Notification shade did not open", device.openNotification())
            // The custom views show the headline ("+1,240 mA · 5.2 W", collapsed) or the state ("Charging · AC charger", expanded).
            val shown = Pattern.compile(".+ m?A · .+ W|" + Pattern.quote(charging) + "( · .+)?")
            val row = device.wait(Until.findObject(By.pkg("com.android.systemui").text(shown)), 120_000)
            DeviceEnvironment.screenshot("notification-charging-simulated-battery")
            assertNotNull("Monitoring notification is missing from SystemUI", row)
            row!!.click()
            // Since P3a the tap sends destination=now: the Now tab, no longer the drain details.
            val opened = device.wait(Until.hasObject(By.pkg(context.packageName).res(TestTags.TAB_NOW).selected(true)), 120_000)
            DeviceEnvironment.screenshot("notification-opens-now")
            assertTrue("Tapping the monitoring notification must open Now", opened)
            phase = "stop monitoring"
            context.stopService(service)
            withTimeout(120_000) { repo.observation.first { it.stopped } }
            await { notification() == null }
            val count = BatteryGraph.db.batteryDao().count()
            val polled = repo.realtimeFlow.value.sample?.elapsedMs ?: 0
            delay(6_500)
            assertTrue("Demand keeps realtime polls running after stop", (repo.realtimeFlow.value.sample?.elapsedMs ?: 0) > polled)
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
            demand.close()
            device.executeShellCommand("dumpsys battery reset")
            device.wakeUp()
            // onNewIntent retains OPEN_DRAIN in production. ActivityScenario matches
            // lifecycle callbacks against its launch intent, including DESTROYED.
            InstrumentationRegistry.getInstrumentation().runOnMainSync { ownActivity.intent = launchIntent }
            scenario.close()
        }
    }
}
