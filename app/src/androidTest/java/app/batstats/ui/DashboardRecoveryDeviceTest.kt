package app.batstats.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.batstats.R
import app.batstats.battery.data.RepositoryRecoveryTest
import app.batstats.test.DeviceEnvironment
import app.batstats.ui.screens.DashboardScreen
import app.batstats.ui.theme.MainTheme
import app.batstats.viewmodel.DashboardViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real production composable; battery inputs/failures/storage are explicitly isolated and scripted. */
@RunWith(AndroidJUnit4::class)
class DashboardRecoveryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun unavailablePartialAndRecoveredReadingsAreVisibleWithoutInventingAnObservation() {
        DeviceEnvironment.requireDisposableEmulator()
        val fixture = RepositoryRecoveryTest.Fixture()
        val models = ViewModelStore()
        val vm = DashboardViewModel(ApplicationProvider.getApplicationContext<Application>(), fixture.repository, fixture.settings)
        models.put("scripted-dashboard", vm)
        val context = DeviceEnvironment.context
        fun refresh() = compose.onNodeWithText(context.getString(R.string.diagnostic_refresh)).performClick()
        fun showIssue(text: String) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))
            compose.onNodeWithText(text, substring = true).assertIsDisplayed()
        }
        try {
            fixture.context.missingBattery = true
            compose.setContent {
                MainTheme(darkTheme = false, dynamicColor = false) {
                    DashboardScreen({}, {}, {}, {}, {}, {}, {}, vm)
                }
            }
            compose.waitUntil(120_000) { fixture.repository.error.value?.contains("not supplied") == true }
            compose.onNodeWithText(context.getString(R.string.monitor_waiting_battery)).assertIsDisplayed()
            showIssue("not supplied")
            DeviceEnvironment.screenshot("dashboard-unavailable-scripted")
            fixture.context.missingBattery = false
            fixture.context.throwOnBattery = true
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(context.getString(R.string.diagnostic_refresh)))
            refresh()
            compose.waitUntil(120_000) { fixture.repository.error.value?.contains("SecurityException") == true }
            showIssue("SecurityException")
            DeviceEnvironment.screenshot("dashboard-read-error-scripted")
            fixture.context.throwOnBattery = false
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(context.getString(R.string.diagnostic_refresh)))
            refresh()
            compose.waitUntil(120_000) { fixture.repository.error.value == null && fixture.repository.realtimeFlow.value.level == 80 }
            compose.onNodeWithText("80%").assertIsDisplayed()
            assertNull(fixture.repository.observation.value.startedAt)
            DeviceEnvironment.screenshot("dashboard-recovered-scripted")
            fixture.context.rejectEvents = true
            fixture.repository.startSampling()
            compose.waitUntil(120_000) { fixture.repository.error.value?.contains("State events") == true }
            showIssue("State events")
            assertNull(fixture.repository.observation.value.startedAt)
            assertEquals(0L, fixture.repository.observation.value.screenOff.durationMs)
            assertNull(fixture.repository.observation.value.screenOff.chargeMah)
            DeviceEnvironment.screenshot("dashboard-partial-observation-paused-scripted")
        } finally {
            compose.runOnUiThread { models.clear() }
            runBlocking { fixture.close() }
        }
    }
}
