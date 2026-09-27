package app.batstats.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.batstats.R
import app.batstats.battery.data.RepositoryRecoveryTest
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import app.batstats.test.DeviceEnvironment
import app.batstats.ui.screens.HistoryScreen
import app.batstats.ui.screens.SessionDetailsScreen
import app.batstats.ui.theme.MainTheme
import app.batstats.viewmodel.HistoryViewModel
import app.batstats.viewmodel.SessionDetailsViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Production history UI and Room, with isolated, explicitly scripted imported readings. */
@RunWith(AndroidJUnit4::class)
class HistoryDetailsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun sessionCardOpensLinkedChartsAndDeletionRemovesStaleDetails() {
        DeviceEnvironment.requireDisposableEmulator()
        val fixture = RepositoryRecoveryTest.Fixture()
        val models = ViewModelStore()
        val history = HistoryViewModel(fixture.repository)
        val details = SessionDetailsViewModel(fixture.repository, fixture.database, "scripted-session")
        models.put("history", history)
        models.put("details", details)
        val context = DeviceEnvironment.context
        val start = 1_779_184_800_000L
        val source = "import:scripted BatteryManager counter observations"
        var opened by mutableStateOf<String?>(null)
        fun show(text: String) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
            compose.onNodeWithText(text).assertIsDisplayed()
        }
        try {
            runBlocking {
                fixture.database.sessionDao().insert(ChargeSession(
                    "scripted-session", SessionType.DISCHARGE, start, start + 180_000,
                    80, 79, 1_000, -60_000, null, observationId = "scripted-import",
                    lastSampleTime = start + 180_000, observedMs = 180_000,
                    counterCoveredMs = 60_000, screenOnMs = 180_000, screenOffMs = 0,
                    source = source
                ))
                listOf(-50_000L, null, -100_000L, -150_000L).forEachIndexed { index, current ->
                    fixture.database.batteryDao().insertSample(BatterySample(
                        timestamp = start + index * 60_000, levelPercent = 80, status = 3,
                        plugged = 0, currentNowUa = current, chargeCounterUah = null,
                        voltageMv = 4000, temperatureDeciC = null, health = null,
                        screenOn = true, observationId = "scripted-import", sessionId = "scripted-session",
                        source = source, boundaryReason = if (index == 2) "gap" else null
                    ))
                }
            }
            compose.setContent {
                MainTheme(darkTheme = false, dynamicColor = false) {
                    if (opened == null) HistoryScreen({}, { opened = it }, history)
                    else SessionDetailsScreen({ opened = null }, details)
                }
            }
            compose.waitUntil(120_000) { !history.ui.value.loading }
            show("80% → 79%")
            DeviceEnvironment.screenshot("history-populated-scripted")
            compose.onNodeWithText("80% → 79%").performClick()
            compose.waitUntil(120_000) { !details.ui.value.loading }
            assertEquals("scripted-session", opened)
            show(context.getString(R.string.session_imported_source, source))
            DeviceEnvironment.screenshot("history-detail-source-scripted")
            show(context.getString(R.string.session_screen_times, "3m 0s", "0s"))
            val currentTitle = context.getString(R.string.session_net_current)
            show("$currentTitle (mA)")
            val chartDescription = context.getString(R.string.chart_description, currentTitle, 3,
                String.format(Locale.getDefault(), "%.1f", -150.0),
                String.format(Locale.getDefault(), "%.1f", -50.0), "mA")
            compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(chartDescription))
            compose.onNodeWithContentDescription(chartDescription).assertIsDisplayed()
            assertTrue(details.ui.value.points.any { it.currentNowUa == null })
            assertTrue(details.ui.value.points.any { it.discontinuity })
            DeviceEnvironment.screenshot("history-current-gaps-scripted")
            show(context.getString(R.string.session_temperature) + " (°C)")
            show(context.getString(R.string.chart_empty))
            DeviceEnvironment.screenshot("history-temperature-unavailable-scripted")
            // A database invalidation must remove the old chart instead of retaining deleted data.
            runBlocking { fixture.database.sessionDao().clearAll() }
            compose.waitUntil(120_000) { !details.ui.value.loading && details.ui.value.session == null }
            show(context.getString(R.string.session_missing))
            assertTrue(details.ui.value.points.isEmpty())
            DeviceEnvironment.screenshot("history-deleted-record-scripted")
        } finally {
            compose.runOnUiThread { models.clear() }
            runBlocking { fixture.close() }
        }
    }
}
