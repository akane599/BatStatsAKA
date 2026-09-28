package app.batstats.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.batstats.R
import app.batstats.battery.BatteryGraph
import app.batstats.battery.apps.AppInfoSource
import app.batstats.battery.apps.AppStatsRepository
import app.batstats.battery.apps.AppUsageStatus
import app.batstats.battery.apps.RoomSessionSnapshotStore
import app.batstats.battery.data.HistoryMaintenance
import app.batstats.battery.data.RepositoryRecoveryTest
import app.batstats.battery.data.db.AppSnapshot
import app.batstats.battery.data.db.AppSnapshotKind
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.DailySummary
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.measurement.DailySummaryAggregator
import app.batstats.test.DeviceEnvironment
import app.batstats.ui.screens.HistoryScreen
import app.batstats.ui.screens.SessionDetailsScreen
import app.batstats.ui.theme.MainTheme
import app.batstats.viewmodel.DefaultHistoryRepository
import app.batstats.viewmodel.DefaultSessionDetailsRepository
import app.batstats.viewmodel.HistoryMode
import app.batstats.viewmodel.HistoryViewModel
import app.batstats.viewmodel.SessionDetailsUiState
import app.batstats.viewmodel.SessionDetailsViewModel
import app.batstats.viewmodel.SessionFilter
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Production History and SessionDetails UI over Room, with scripted rows: the Days totals, the session row, the chips,
 * a row opening its session, the session's details, deleting it (row, readings and snapshots go; History updates),
 * and a session deleted elsewhere. The last test drives [app.batstats.battery.data.db.SessionDao.deleteSession] directly.
 */
@RunWith(AndroidJUnit4::class)
class HistoryDetailsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun detailsFor(fixture: RepositoryRecoveryTest.Fixture, id: String): SessionDetailsViewModel {
        val koin = BatteryGraph.getKoin()
        val source = DefaultSessionDetailsRepository(
            fixture.repository, fixture.database, fixture.calibration, koin.get<AppStatsRepository>(),
            RoomSessionSnapshotStore(fixture.database), fixture.settings, HistoryMaintenance(),
        )
        return SessionDetailsViewModel(source, koin.get<AppInfoSource>(), id)
    }

    @Test fun historyRowsOpenTheirSessionAndDeletingItRemovesItsRecords() {
        DeviceEnvironment.requireDisposableEmulator()
        val fixture = RepositoryRecoveryTest.Fixture()
        val models = ViewModelStore()
        val history = HistoryViewModel(DefaultHistoryRepository(fixture.repository, fixture.database), SavedStateHandle())
        val details = detailsFor(fixture, "scripted-session")
        val elsewhere = detailsFor(fixture, "elsewhere-session")
        models.put("history", history)
        models.put("details", details)
        models.put("elsewhere", elsewhere)
        val context = DeviceEnvironment.context
        fun text(id: Int, vararg args: Any): String = context.getString(id, *args)
        val today = DailySummaryAggregator.epochDay(System.currentTimeMillis(), ZoneId.systemDefault())
        val start = 1_779_184_800_000L
        val source = "import:scripted BatteryManager counter observations"
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
                    source = source,
                    appUsageStatus = AppUsageStatus.NO_ACCESS,
                ))
                listOf(-50_000L, null, -100_000L, -150_000L).forEachIndexed { index, current ->
                    fixture.database.batteryDao().insertSample(BatterySample(
                        timestamp = start + index * 60_000, levelPercent = 80 - index / 3, status = 3,
                        plugged = 0, currentNowUa = current, chargeCounterUah = null,
                        voltageMv = 4000, temperatureDeciC = null, health = null,
                        screenOn = true, observationId = "scripted-import", sessionId = "scripted-session",
                        source = source, boundaryReason = if (index == 2) "gap" else null,
                    ))
                }
                fixture.database.appUsageDao().insertSnapshotHeader(
                    AppSnapshot(sessionId = "scripted-session", kind = AppSnapshotKind.BASELINE, capturedAt = start, windowStartedAt = null, windowStartCount = null),
                )
                fixture.database.dailySummaryDao().upsert(DailySummary(
                    epochDay = today, screenOnMs = 7_200_000, screenOffMs = 10_800_000,
                    screenOnDischargeUah = 300_000, screenOffDischargeUah = 120_000,
                ))
            }
            compose.setContent {
                MainTheme(dynamicColor = false) {
                    when (opened) {
                        null -> HistoryScreen(onOpenSession = { opened = it }, vm = history)
                        "scripted-session" -> SessionDetailsScreen(onBack = { opened = null }, onOpenApp = { _, _ -> }, vm = details)
                        else -> SessionDetailsScreen(onBack = { opened = null }, onOpenApp = { _, _ -> }, vm = elsewhere)
                    }
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

            // The whole row opens its session: its type as the title, the chart panel, the stored readings.
            show(sessionDetail)
            compose.onNodeWithText(sessionDetail).performClick()
            compose.waitUntil(120_000) { details.state.value is SessionDetailsUiState.Ready }
            assertEquals("scripted-session", opened)
            compose.onNodeWithText(text(R.string.sessiondetails_type_discharge)).assertIsDisplayed()
            show(text(R.string.sessiondetails_chart_title))
            DeviceEnvironment.screenshot("session-details-scripted")

            // Delete: Cancel keeps it; Delete removes the row, its readings and snapshots, and goes back to History.
            compose.onNodeWithContentDescription(text(R.string.sessiondetails_delete)).performClick()
            compose.onNodeWithText(text(R.string.sessiondetails_delete_title)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.sessiondetails_delete_body)).assertIsDisplayed()
            DeviceEnvironment.screenshot("session-details-delete-confirm")
            compose.onNodeWithText(text(R.string.sessiondetails_cancel)).performClick()
            compose.onAllNodesWithText(text(R.string.sessiondetails_delete_title)).assertCountEquals(0)
            assertNotNull(runBlocking { fixture.database.sessionDao().byId("scripted-session") })

            compose.onNodeWithContentDescription(text(R.string.sessiondetails_delete)).performClick()
            compose.onNodeWithText(text(R.string.sessiondetails_delete_confirm)).performClick()
            compose.waitUntil(120_000) { opened == null }
            compose.waitUntil(120_000) { history.state.value.sessions.rows.isEmpty() }
            show(text(R.string.history_sessions_empty_all))
            compose.onAllNodesWithText(sessionDetail).assertCountEquals(0)
            runBlocking {
                assertNull(fixture.database.sessionDao().byId("scripted-session"))
                assertTrue(fixture.database.batteryDao().samplesForSession("scripted-session").first().isEmpty())
                assertTrue(fixture.database.appUsageDao().snapshots().isEmpty())
                // Daily totals stay.
                assertNotNull(fixture.database.dailySummaryDao().day(today).first())
            }
            DeviceEnvironment.screenshot("history-after-session-delete")

            // Deleted elsewhere (e.g. a clear) while its details are open: "not found", and its action goes back.
            runBlocking {
                fixture.database.sessionDao().insert(ChargeSession(
                    "elsewhere-session", SessionType.CHARGE, start, start + 600_000, 40, 60, null, null, null,
                    lastSampleTime = start + 600_000, source = source,
                ))
            }
            compose.runOnUiThread { opened = "elsewhere-session" }
            compose.waitUntil(120_000) { elsewhere.state.value is SessionDetailsUiState.Ready }
            runBlocking { fixture.database.sessionDao().clearAll() }
            compose.waitUntil(120_000) { elsewhere.state.value == SessionDetailsUiState.Missing }
            compose.onNodeWithText(text(R.string.sessiondetails_missing_title)).assertIsDisplayed()
            DeviceEnvironment.screenshot("session-details-missing")
            compose.onNodeWithText(text(R.string.sessiondetails_missing_action)).performClick()
            compose.waitUntil(120_000) { opened == null }
        } finally {
            compose.runOnUiThread { models.clear() }
            runBlocking { fixture.close() }
        }
    }

    @Test fun deletingASessionRefusesTheOneBeingRecorded(): Unit = runBlocking {
        DeviceEnvironment.requireDisposableEmulator()
        val fixture = RepositoryRecoveryTest.Fixture()
        try {
            val sessions = fixture.database.sessionDao()
            sessions.insert(ChargeSession(
                "open", SessionType.DISCHARGE, 1_000, null, 80, null, null, null, null,
                activeKey = 1, observationId = "generation-a", lastSampleTime = 2_000, source = "BatteryManager observed interval",
            ))
            fixture.database.batteryDao().insertSample(BatterySample(
                timestamp = 1_500, levelPercent = 80, status = 3, plugged = 0, currentNowUa = -100_000, chargeCounterUah = null,
                voltageMv = 4000, temperatureDeciC = null, health = null, screenOn = true, observationId = "generation-a",
                sessionId = "open", source = "BatteryManager",
            ))
            // The writer records generation-a: refused, nothing deleted.
            assertFalse(sessions.deleteSession("open", recordingGeneration = "generation-a"))
            assertNotNull(sessions.byId("open"))
            assertEquals(1, fixture.database.batteryDao().count())
            // Left open by a stopped process (monitoring off, or another generation): it can go.
            assertTrue(sessions.deleteSession("open", recordingGeneration = "generation-b"))
            assertNull(sessions.byId("open"))
            assertEquals(0, fixture.database.batteryDao().count())
            // Already gone.
            assertFalse(sessions.deleteSession("open", recordingGeneration = null))
        } finally {
            fixture.close()
        }
    }
}
