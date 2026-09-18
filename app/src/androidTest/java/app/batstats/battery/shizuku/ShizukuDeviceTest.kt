package app.batstats.battery.shizuku

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import app.batstats.battery.BatteryGraph
import app.batstats.battery.BatteryMainActivity
import app.batstats.battery.util.DetailedStatsCollector
import app.batstats.battery.util.ShellRunner
import app.batstats.test.DeviceEnvironment
import app.batstats.test.RequiresShizuku
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import rikka.shizuku.Shizuku

/** Real Shizuku 13.6 Binder/shell integration. Run scripts/prepare_shizuku.py first. */
@RequiresShizuku
@RunWith(AndroidJUnit4::class)
class ShizukuDeviceTest {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val device get() = DeviceEnvironment.device
    private suspend fun await(condition: () -> Boolean) = withTimeout(120_000) { while (!condition()) delay(100) }

    @Test fun shellServiceAuthorizationHelperRestartAndAccessLossPreserveOrdinaryReadings() = runBlocking {
        DeviceEnvironment.requireDisposableEmulator()
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        val scenario = ActivityScenario.launch(BatteryMainActivity::class.java)
        val bridge = GlobalContext.get().get<ShizukuBridge>()
        val shell = GlobalContext.get().get<ShellRunner>()
        val collector = GlobalContext.get().get<DetailedStatsCollector>()
        var stoppedServer = false
        try {
            await { bridge.ping() }
            assertEquals("Validate shell mode, not root", 2000, Shizuku.getUid())
            if (!bridge.hasPermission()) {
                assertEquals(ShizukuBridge.Failure.NO_PERMISSION,
                    (bridge.run("dumpsys battery") as ShizukuBridge.RunResult.Error).reason)
                scenario.onActivity { bridge.requestPermission() }
                val allow = device.wait(Until.findObject(By.res("moe.shizuku.privileged.api", "button1")), 120_000)
                assertNotNull("Shizuku authorization dialog did not appear", allow)
                DeviceEnvironment.screenshot("shizuku-authorization")
                allow!!.click()
                await { bridge.hasPermission() }
            }
            assertTrue(bridge.running.value)
            assertTrue(bridge.granted.value)
            assertEquals(ShellRunner.Mode.SHIZUKU, shell.detectMode(true))
            val first = bridge.run("dumpsys battery")
            assertTrue("Battery command failed: $first", first is ShizukuBridge.RunResult.Success)
            assertTrue((first as ShizukuBridge.RunResult.Success).output.contains("level:"))
            val forbidden = bridge.run("id")
            assertTrue(forbidden is ShizukuBridge.RunResult.Error)
            assertEquals(ShizukuBridge.Failure.COMMAND, (forbidden as ShizukuBridge.RunResult.Error).reason)
            bridge.unbind() // Destroy and restart the real helper, with authorization retained.
            assertTrue(bridge.run("dumpsys battery") is ShizukuBridge.RunResult.Success)
            assertTrue("Android16 report must have a valid window", collector.refresh(force = true))
            assertNotNull(collector.snapshot.value)
            assertTrue(collector.snapshot.value!!.source.contains("SHIZUKU"))
            DeviceEnvironment.screenshot("shizuku-connected")

            val pids = device.executeShellCommand("pidof shizuku_server").trim()
            assertTrue("Expected one disposable Shizuku server", pids.matches(Regex("[0-9]+")))
            device.executeShellCommand("kill $pids")
            stoppedServer = true
            await { !bridge.running.value && !bridge.granted.value }
            assertEquals(ShizukuBridge.Failure.NOT_RUNNING,
                (bridge.run("dumpsys battery") as ShizukuBridge.RunResult.Error).reason)
            collector.accessChanged(shell.detectMode(true))
            assertNotEquals(ShellRunner.Mode.SHIZUKU, shell.access.value)
            assertNull("No stale advanced report after losing its source", collector.snapshot.value)
            val reading = CompletableDeferred<Int?>()
            BatteryGraph.repo.refreshNow { reading.complete(it.level) }
            assertNotNull("Ordinary battery data survives Shizuku loss", withTimeout(120_000) { reading.await() })
            DeviceEnvironment.screenshot("shizuku-disconnected-ordinary-reading")

            device.executeShellCommand("/data/local/tmp/batstats-shizuku-starter")
            await { bridge.ping() && bridge.hasPermission() }
            stoppedServer = false
            assertEquals(ShellRunner.Mode.SHIZUKU, shell.detectMode(true))
            assertTrue(bridge.run("dumpsys battery") is ShizukuBridge.RunResult.Success)
            assertTrue(collector.refresh(force = true))
            assertNotNull(collector.snapshot.value)
            DeviceEnvironment.screenshot("shizuku-reconnected")
        } catch (failure: Throwable) {
            runCatching { DeviceEnvironment.screenshot("shizuku-failure") }
                .exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            if (stoppedServer) device.executeShellCommand("/data/local/tmp/batstats-shizuku-starter")
            bridge.unbind()
            scenario.close()
        }
    }
}
