package app.batstats.battery.widget

import android.Manifest
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.batstats.R
import app.batstats.battery.BatteryGraph
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.service.BatteryMonitorService
import app.batstats.test.DeviceEnvironment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.text.DateFormat
import java.util.Date

/** Real system widget binding/delivery, with explicitly scripted readings in a test-owned host. */
@RunWith(AndroidJUnit4::class)
class WidgetDeliveryDeviceTest {
    @Test fun deliveredWidgetsRetainFreshnessAndNeverTurnMissingReadingsIntoZero(): Unit = runBlocking {
        DeviceEnvironment.requireDisposableEmulator()
        val context = DeviceEnvironment.context
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        DeviceEnvironment.device.wakeUp()
        DeviceEnvironment.device.executeShellCommand("wm dismiss-keyguard")
        context.stopService(Intent(context, BatteryMonitorService::class.java))
        BatteryGraph.repo.stopSampling()
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        val manager = AppWidgetManager.getInstance(context)
        val widgets = mutableListOf<AppWidgetHostView>()
        var host: AppWidgetHost? = null
        var phase = "bind and receive initial widget views"
        // Binding a widget makes its provider schedule its own refresh from the real
        // battery, and that push can land after a scripted one and replace it, stranding
        // the expected values for the rest of the wait. Re-apply the scripted state every
        // two seconds so a late real reading cannot win the race. The values each phase
        // asserts are unchanged; only their delivery is repeated.
        suspend fun awaitViews(reapply: () -> Unit = {}, condition: () -> Boolean) = withTimeout(120_000) {
            var polls = 0
            while (true) {
                var ready = false
                scenario.onActivity { ready = condition() }
                if (ready) break
                delay(100)
                if (++polls % 20 == 0) reapply()
            }
        }
        fun value(index: Int) = widgets[index].findViewById<TextView>(R.id.value)?.text?.toString()
        fun captions() = widgets.map { it.findViewById<TextView>(R.id.subtitle)?.text?.toString() }
        try {
            automation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
            try {
                scenario.onActivity { activity ->
                    val widgetHost = AppWidgetHost(activity, 736)
                    host = widgetHost
                    widgetHost.deleteHost() // Only this development package's test host.
                    widgetHost.startListening()
                    val column = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        val inset = (24 * resources.displayMetrics.density).toInt()
                        setPadding(inset, inset * 2, inset, inset)
                    }
                    listOf(BatteryLevelWidget::class.java, BatteryTempWidget::class.java,
                        BatteryTimeWidget::class.java).forEach { provider ->
                        val id = widgetHost.allocateAppWidgetId()
                        assertTrue("Widget binding failed: ${provider.simpleName}",
                            manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, provider)))
                        val info = manager.getAppWidgetInfo(id)
                        assertNotNull("Bound widget metadata unavailable", info)
                        val view = widgetHost.createView(activity, id, info)
                        widgets.add(view)
                        column.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            (140 * activity.resources.displayMetrics.density).toInt()))
                    }
                    activity.setContentView(column)
                }
            } finally {
                automation.dropShellPermissionIdentity()
            }
            awaitViews { captions().all { !it.isNullOrEmpty() } }
            phase = "refresh barrier"
            instrumentation.waitForIdleSync()
            val refreshed = CompletableDeferred<Unit>()
            BatteryGraph.repo.refreshNow { refreshed.complete(Unit) }
            withTimeout(120_000) { refreshed.await() }
            val sample = BatterySample(timestamp = 1_779_184_800_000L, levelPercent = 80, status = 3,
                plugged = 0, currentNowUa = null, chargeCounterUah = null, voltageMv = 4000,
                temperatureDeciC = 250, health = null, screenOn = true, etaMs = 7_200_000)
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(sample.timestamp))
            phase = "paused scripted values"
            WidgetUpdater.push(context, sample, monitoring = false, fahrenheit = false)
            awaitViews(reapply = { WidgetUpdater.push(context, sample, monitoring = false, fahrenheit = false) }) {
                value(0) == "80%" && value(1) == "25.0 °C" && value(2) == "—" &&
                    captions().all { it == context.getString(R.string.widget_paused_at, date) } }
            DeviceEnvironment.screenshot("widgets-paused-scripted")
            phase = "Fahrenheit and estimate"
            WidgetUpdater.push(context, sample, monitoring = true, fahrenheit = true)
            awaitViews(reapply = { WidgetUpdater.push(context, sample, monitoring = true, fahrenheit = true) }) {
                value(1) == "77.0 °F" && value(2) == context.getString(R.string.monitor_eta_remaining, "2h 0m") &&
                    captions().all { it == context.getString(R.string.widget_read_at, date) } }
            DeviceEnvironment.screenshot("widgets-fahrenheit-estimate-scripted")
            phase = "unavailable values"
            WidgetUpdater.showPlaceholder(context)
            awaitViews(reapply = { WidgetUpdater.showPlaceholder(context) }) {
                widgets.indices.all { value(it) == "—" } && captions().all {
                    it == context.getString(R.string.widget_paused_at, context.getString(R.string.widget_no_reading)) } }
            DeviceEnvironment.screenshot("widgets-unavailable-scripted")
        } catch (failure: Throwable) {
            runCatching { DeviceEnvironment.screenshot("widgets-failure") }
                .exceptionOrNull()?.let(failure::addSuppressed)
            var actual = "Views could not be inspected"
            runCatching { instrumentation.runOnMainSync {
                actual = "values=${widgets.indices.map { value(it) }}; captions=${captions()}"
            } }.exceptionOrNull()?.let(failure::addSuppressed)
            throw AssertionError("Widget failure during $phase; $actual", failure)
        } finally {
            scenario.onActivity { host?.stopListening(); host?.deleteHost() }
            scenario.close()
        }
    }
}
