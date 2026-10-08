package com.akane.voltwise.battery.widget

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
import com.akane.voltwise.R
import com.akane.voltwise.battery.BatteryGraph
import com.akane.voltwise.battery.data.db.BatterySample
import com.akane.voltwise.battery.service.BatteryMonitorService
import com.akane.voltwise.test.DeviceEnvironment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.text.format.DateFormat as AndroidDateFormat

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
        suspend fun awaitViews(condition: () -> Boolean) = withTimeout(120_000) {
            while (true) {
                var ready = false
                scenario.onActivity { ready = condition() }
                if (ready) break
                delay(100)
            }
        }
        // Each bound widget's onUpdate starts a host refresh (a live, unsaved reading), and those broadcasts can arrive
        // after the refresh barrier: one landing just after a scripted push replaces it. Push again until it shows.
        suspend fun pushUntil(push: () -> Unit, condition: () -> Boolean) = withTimeout(120_000) {
            while (true) {
                push()
                repeat(REPUSH_POLLS) {
                    var ready = false
                    scenario.onActivity { ready = condition() }
                    if (ready) return@withTimeout
                    delay(100)
                }
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
            // Independently resolve the system-clock contract, rather than the locale-only SHORT style.
            val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
            val skeleton = if (AndroidDateFormat.is24HourFormat(context)) "yMdHm" else "yMdhm"
            val pattern = AndroidDateFormat.getBestDateTimePattern(locale, skeleton)
            val date = SimpleDateFormat(pattern, locale).format(Date(sample.timestamp))
            // c79c379 localized widget percent/duration text; resolve the fixture's resources independently.
            val numbers = NumberFormat.getNumberInstance(locale)
            val expectedLevel = context.getString(R.string.percent_value, numbers.format(80))
            val expectedDuration = context.getString(
                R.string.now_duration_hours_minutes, numbers.format(2), numbers.format(0),
            )
            val expectedEstimate = context.getString(R.string.monitor_eta_remaining, expectedDuration)
            val expectedCelsius = String.format(Locale.getDefault(), "%.1f °C", 25.0)
            val expectedFahrenheit = String.format(Locale.getDefault(), "%.1f °F", 77.0)
            phase = "paused scripted values"
            pushUntil({ WidgetUpdater.push(context, sample, monitoring = false, fahrenheit = false) }) {
                value(0) == expectedLevel && value(1) == expectedCelsius && value(2) == "—" &&
                    captions().all { it == context.getString(R.string.widget_paused_at, date) }
            }
            DeviceEnvironment.screenshot("widgets-paused-scripted")
            phase = "Fahrenheit and estimate"
            pushUntil({ WidgetUpdater.push(context, sample, monitoring = true, fahrenheit = true) }) {
                value(0) == expectedLevel && value(1) == expectedFahrenheit && value(2) == expectedEstimate &&
                    captions().all { it == context.getString(R.string.widget_read_at, date) }
            }
            DeviceEnvironment.screenshot("widgets-fahrenheit-estimate-scripted")
            phase = "unavailable values"
            pushUntil({ WidgetUpdater.showPlaceholder(context) }) {
                widgets.indices.all { value(it) == "—" } && captions().all {
                    it == context.getString(R.string.widget_paused_at, context.getString(R.string.widget_no_reading))
                }
            }
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

    private companion object {
        /** 100 ms polls before a scripted push is repeated. */
        const val REPUSH_POLLS = 20
    }
}
