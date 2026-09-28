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
import app.batstats.battery.apps.AppUsageStatus
import app.batstats.battery.data.RepositoryRecoveryTest
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.DailySummary
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.measurement.DailySummaryAggregator
import app.batstats.test.DeviceEnvironment
import app.batstats.ui.screens.HistoryScreen
import app.batstats.ui.theme.MainTheme
import app.batstats.viewmodel.DefaultHistoryRepository
import app.batstats.viewmodel.HistoryMode
import app.batstats.viewmodel.HistoryViewModel
import app.batstats.viewmodel.SessionFilter
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Production History UI and Room, with scripted rows: the Days totals, the session row, the chips, and a row opening
 * its session. The SessionDetails half (source, charts, deletion of an open record) is rewritten with that screen
 * and re-added after the P4b merge.
 */
@RunWith(AndroidJUnit4::class)
class HistoryDetailsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun daysAndSessionRowsShowScriptedHistoryAndARowOpensItsSession() {
        DeviceEnvironment.requireDisposableEmulator()
        val fixture = RepositoryRecoveryTest.Fixture()
        val models = ViewModelStore()
        val history = HistoryViewModel(DefaultHistoryRepository(fixture.repository, fixture.database))
        models.put("history", history)
        val context = DeviceEnvironment.context
        fun text(id: Int, vararg args: Any): String = context.getString(id, *args)
        val today = DailySummaryAggregator.epochDay(System.currentTimeMillis(), ZoneId.systemDefault())
        val start = 1_779_184_800_000L
        var opened by mutableStateOf<String?>(null)
        fun show(value: String) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(value))
            compose.onNodeWithText(value).assertIsDisplayed()
        }
        // "3 min · 80% → 79%": the session row's second line.
        val sessionDetail = text(
            R.string.history_session_detail,
            text(R.string.now_duration_minutes, "3"),
            text(R.string.history_level_change, text(R.string.history_percent, "80"), text(R.string.history_percent, "79")),
        )
        try {
            runBlocking {
                fixture.database.sessionDao().insert(ChargeSession(
                    "scripted-session", SessionType.DISCHARGE, start, start + 180_000,
                    80, 79, 42_000, -60_000, null, observationId = "scripted-import",
                    lastSampleTime = start + 180_000, observedMs = 180_000,
                    counterCoveredMs = 60_000, screenOnMs = 180_000, screenOffMs = 0,
                    source = "import:scripted BatteryManager counter observations",
                    appUsageStatus = AppUsageStatus.NO_ACCESS,
                ))
                fixture.database.dailySummaryDao().upsert(DailySummary(
                    epochDay = today, screenOnMs = 7_200_000, screenOffMs = 10_800_000,
                    screenOnDischargeUah = 300_000, screenOffDischargeUah = 120_000,
                ))
            }
            compose.setContent {
                MainTheme(dynamicColor = false) {
                    HistoryScreen(onOpenSession = { opened = it }, vm = history)
                }
            }

            // Days (the default): today's row with the drain used and screen-on time.
            compose.waitUntil(120_000) { !history.state.value.days.loading && history.state.value.days.recorded }
            compose.onNodeWithText(text(R.string.history_mode_days)).assertIsSelected()
            show(text(R.string.history_days_title))
            show("420 mAh")
            show(text(R.string.history_day_detail, text(R.string.now_duration_hours_minutes, "2", "0")))
            DeviceEnvironment.screenshot("history-days-scripted")

            // Sessions: the row, its charge moved out of the battery and the quiet app-usage hint.
            compose.onNodeWithText(text(R.string.history_mode_sessions)).performClick()
            compose.waitUntil(120_000) {
                history.state.value.mode == HistoryMode.SESSIONS && history.state.value.sessions.rows.isNotEmpty()
            }
            show(sessionDetail)
            show("−42 mAh")
            show(text(R.string.history_app_usage_no_access))
            DeviceEnvironment.screenshot("history-sessions-scripted")

            // The Charge chip hides the discharge; All brings it back.
            compose.onNodeWithText(text(R.string.history_filter_charge)).performClick()
            compose.waitUntil(120_000) {
                history.state.value.filter == SessionFilter.CHARGE && history.state.value.sessions.rows.isEmpty()
            }
            show(text(R.string.history_sessions_empty_charge))
            compose.onAllNodesWithText(sessionDetail).assertCountEquals(0)
            DeviceEnvironment.screenshot("history-sessions-charge-empty")
            compose.onNodeWithText(text(R.string.history_filter_all)).performClick()
            compose.waitUntil(120_000) { history.state.value.sessions.rows.isNotEmpty() }

            // The whole row opens its session.
            show(sessionDetail)
            compose.onNodeWithText(sessionDetail).performClick()
            compose.waitForIdle()
            assertEquals("scripted-session", opened)
        } finally {
            compose.runOnUiThread { models.clear() }
            runBlocking { fixture.close() }
        }
    }
}
