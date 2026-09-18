package app.batstats.ui

import android.Manifest
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.batstats.R
import app.batstats.battery.BatteryGraph
import app.batstats.battery.BatteryMainActivity
import app.batstats.battery.service.BatteryMonitorService
import app.batstats.settings.AppSettings
import app.batstats.test.DeviceEnvironment
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationDeviceTest {
    @get:Rule(order = 0) val permission = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<BatteryMainActivity>()
    private lateinit var savedSettings: AppSettings
    private var savedFont = "1.0"
    private fun label(id: Int) = DeviceEnvironment.context.getString(id)
    private fun click(id: Int) = compose.onNodeWithText(label(id)).performClick()
    private fun scroll(id: Int) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label(id)))
        compose.onNodeWithText(label(id)).assertIsDisplayed()
    }
    private fun back() { DeviceEnvironment.device.pressBack(); compose.waitForIdle() }
    private fun capture(name: String) { compose.waitForIdle(); DeviceEnvironment.screenshot(name) }

    @Before fun prepare() = runBlocking {
        DeviceEnvironment.requireDisposableEmulator()
        savedFont = DeviceEnvironment.device.executeShellCommand("settings get system font_scale").trim()
        savedSettings = BatteryGraph.settings.flow.first()
        DeviceEnvironment.context.stopService(Intent(DeviceEnvironment.context, BatteryMonitorService::class.java))
        BatteryGraph.repo.stopSampling()
        BatteryGraph.settings.update { it.copy(themeIndex = 1, dynamicColors = false) }
    }

    @After fun restore() = runBlocking {
        if (::savedSettings.isInitialized) BatteryGraph.settings.update { savedSettings }
        if (savedFont.matches(Regex("[0-9.]+"))) DeviceEnvironment.device.executeShellCommand("settings put system font_scale $savedFont")
        else DeviceEnvironment.device.executeShellCommand("settings delete system font_scale")
    }

    @Test fun screensRemainReachableAndInvalidImportPreservesSettings() {
        compose.waitUntil(120_000) { BatteryGraph.repo.realtimeFlow.value.level != null }
        capture("dashboard-light")
        scroll(R.string.adv_title); click(R.string.adv_title)
        compose.onNodeWithText(label(R.string.adv_access_help)).assertIsDisplayed()
        capture("advanced-access")
        click(R.string.adv_access_help)
        compose.onNodeWithText(label(R.string.adv_copy_commands)).assertIsDisplayed()
        capture("access-instructions")
        back(); back()
        scroll(R.string.diagnostics_title); click(R.string.diagnostics_title)
        scroll(R.string.diagnostic_events); capture("diagnostics-events")
        back()
        scroll(R.string.history); click(R.string.history)
        compose.onNode(hasSetTextAction()).performTextInput("no-such-observation-device-test")
        compose.waitUntil(120_000) { compose.onAllNodesWithText(label(R.string.history_no_matches)).fetchSemanticsNodes().isNotEmpty() }
        scroll(R.string.history_no_matches); capture("history-empty-filter")
        compose.onNodeWithContentDescription(label(R.string.back)).performClick()
        scroll(R.string.data_export_import); click(R.string.data_export_import)
        compose.onNodeWithText(label(R.string.import_csv)).performScrollTo().assertIsDisplayed()
        capture("history-export-import")
        back()
        compose.onNodeWithContentDescription(label(R.string.settings)).performClick()
        compose.onNodeWithContentDescription(label(R.string.settings_more)).performClick()
        click(R.string.import_settings_desc)
        val before = runBlocking { BatteryGraph.settings.flow.first() }
        compose.onNode(hasSetTextAction()).performTextInput("{invalid}")
        click(R.string.import_action)
        compose.waitUntil(120_000) { compose.onAllNodesWithText(label(R.string.settings_invalid_import)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label(R.string.settings_invalid_import)).performScrollTo().assertIsDisplayed()
        Assert.assertEquals(before, runBlocking { BatteryGraph.settings.flow.first() })
        capture("settings-invalid-import")
        click(R.string.cancel)
    }

    @Test fun largeTextDarkThemeKeepsActionsAndResetExplanationReachable() {
        DeviceEnvironment.device.executeShellCommand("settings put system font_scale 2.0")
        runBlocking { BatteryGraph.settings.update { it.copy(themeIndex = 2, dynamicColors = false) } }
        compose.waitUntil(120_000) { compose.activity.resources.configuration.fontScale >= 1.99f }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label(R.string.diagnostic_refresh)))
        capture("dashboard-dark-font200")
        scroll(R.string.monitor_details); click(R.string.monitor_details)
        scroll(R.string.monitor_counter_help); capture("observation-dark-font200")
        back()
        compose.onNodeWithContentDescription(label(R.string.settings)).performClick()
        compose.onNodeWithContentDescription(label(R.string.settings_more)).performClick()
        click(R.string.reset_settings_desc)
        compose.onNodeWithText(label(R.string.reset_all_settings)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.reset_all)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.cancel)).assertIsDisplayed()
        capture("settings-reset-dark-font200")
        click(R.string.cancel) // Inspect the destructive control without resetting preferences.
    }
}
