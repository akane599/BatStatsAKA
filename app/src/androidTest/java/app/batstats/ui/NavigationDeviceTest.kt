package app.batstats.ui

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.batstats.R
import app.batstats.battery.BatteryGraph
import app.batstats.battery.BatteryMainActivity
import app.batstats.battery.service.BatteryMonitorService
import app.batstats.settings.AppSettings
import app.batstats.test.DeviceEnvironment
import app.batstats.ui.TestTags
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NavigationDeviceTest {
    @get:Rule(order = 0) val permission = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<BatteryMainActivity>()
    private lateinit var savedSettings: AppSettings
    private var savedFont: String? = null
    private var changedOrientation = false
    private fun label(id: Int) = DeviceEnvironment.context.getString(id)
    private fun click(id: Int) = compose.onNodeWithText(label(id)).performClick()
    private fun scroll(id: Int) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label(id)))
        compose.onNodeWithText(label(id)).assertIsDisplayed()
    }
    private fun nowShowing() = compose.onAllNodes(
        hasText(label(R.string.now_start_monitoring)) or hasText(label(R.string.now_stop_monitoring)),
    ).fetchSemanticsNodes().isNotEmpty()
    private fun awaitNow() {
        compose.waitUntil(120_000) { nowShowing() }
        compose.onNodeWithTag(TestTags.TAB_NOW).assertIsSelected()
    }
    private fun tab(tag: String) = compose.onNodeWithTag(tag).performClick()
    private fun backToNow() {
        // A semantics click completes before recomposition removes a Dialog window.
        // Settle that window before sending Back through the separate Android input path.
        compose.waitForIdle()
        DeviceEnvironment.device.waitForIdle()
        DeviceEnvironment.device.pressBack()
        try { awaitNow() }
        catch (failure: Throwable) {
            runCatching { DeviceEnvironment.screenshot("navigation-back-failure") }
                .exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }
    private fun capture(name: String) { compose.waitForIdle(); DeviceEnvironment.screenshot(name) }

    @Before fun prepare() = runBlocking {
        DeviceEnvironment.requireDisposableEmulator()
        DeviceEnvironment.device.wakeUp()
        DeviceEnvironment.device.executeShellCommand("wm dismiss-keyguard")
        savedFont = DeviceEnvironment.device.executeShellCommand("settings get system font_scale").trim()
        savedSettings = BatteryGraph.settings.flow.first()
        DeviceEnvironment.context.stopService(Intent(DeviceEnvironment.context, BatteryMonitorService::class.java))
        BatteryGraph.repo.stopSampling()
        BatteryGraph.settings.update { it.copy(dynamicColors = false) }
    }

    @After fun restore() = runBlocking {
        if (changedOrientation) {
            DeviceEnvironment.device.setOrientationNatural()
            DeviceEnvironment.device.unfreezeRotation()
        }
        if (::savedSettings.isInitialized) BatteryGraph.settings.update { savedSettings }
        val font = savedFont ?: return@runBlocking
        if (font.matches(Regex("[0-9.]+"))) DeviceEnvironment.device.executeShellCommand("settings put system font_scale $font")
        else DeviceEnvironment.device.executeShellCommand("settings delete system font_scale")
    }

    @Test fun screensRemainReachableAndInvalidImportPreservesSettings() {
        compose.waitUntil(120_000) { BatteryGraph.repo.realtimeFlow.value.level != null }
        awaitNow()
        compose.onNodeWithText(label(R.string.now_start_monitoring)).assertIsDisplayed()
        capture("now-dark")
        scroll(R.string.now_apps_title); capture("now-cards")

        tab(TestTags.TAB_APPS)
        compose.onNodeWithText(label(R.string.adv_access_help)).assertIsDisplayed()
        capture("apps-access")
        click(R.string.adv_access_help)
        compose.onNodeWithText(label(R.string.adv_copy_commands)).assertIsDisplayed()
        capture("access-instructions")
        DeviceEnvironment.device.pressBack()
        compose.waitUntil(120_000) {
            compose.onAllNodesWithText(label(R.string.adv_copy_commands)).fetchSemanticsNodes().isEmpty()
        }
        backToNow()

        tab(TestTags.TAB_HISTORY)
        compose.onNode(hasSetTextAction()).performTextInput("no-such-observation-device-test")
        compose.waitUntil(120_000) { compose.onAllNodesWithText(label(R.string.history_no_matches)).fetchSemanticsNodes().isNotEmpty() }
        scroll(R.string.history_no_matches); capture("history-empty-filter")
        // Back would only close the keyboard here; History's own back arrow returns to Now.
        compose.onNodeWithContentDescription(label(R.string.back)).performClick()
        awaitNow()

        tab(TestTags.TAB_SETTINGS)
        openSettingsData()
        compose.onNodeWithText(label(R.string.data_settings_restore)).performScrollTo().assertIsDisplayed()
        capture("settings-data")
        // An invalid settings file through Data's real restore path; the picker is answered with a file in the app cache.
        val invalid = File(DeviceEnvironment.context.cacheDir, "navigation-invalid-settings.json").apply { writeText("{invalid}") }
        try {
            val before = runBlocking { BatteryGraph.settings.flow.first() }
            answeringOpenDocument(Uri.fromFile(invalid)) { click(R.string.data_settings_restore) }
            compose.waitUntil(120_000) {
                compose.onAllNodesWithText(label(R.string.data_failed_settings_invalid)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(label(R.string.data_failed_settings_invalid)).performScrollTo().assertIsDisplayed()
            Assert.assertEquals(before, runBlocking { BatteryGraph.settings.flow.first() })
            capture("settings-data-invalid-import")
        } finally {
            invalid.delete()
        }
    }

    /** Settings › Data from the Settings tab. */
    private fun openSettingsData() {
        // The interim BatterySettingsContent's literal row; after the P4b Settings merge this is its Data link.
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(EXPORT_DATA_ROW))
        compose.onNodeWithText(EXPORT_DATA_ROW).performClick()
        compose.waitUntil(120_000) {
            compose.onAllNodesWithText(label(R.string.data_stored_title)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Runs [block] while every `ACTION_OPEN_DOCUMENT` this process starts is answered at once with [uri] (the
     * system picker never opens), and waits until one was.
     */
    private fun answeringOpenDocument(uri: Uri, block: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == Intent.ACTION_OPEN_DOCUMENT) {
                    Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri))
                } else {
                    null
                }
        }
        instrumentation.addMonitor(monitor)
        try {
            block()
            compose.waitUntil(10_000) { monitor.hits > 0 }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun largeTextDarkThemeKeepsActionsAndResetExplanationReachable() {
        DeviceEnvironment.device.executeShellCommand("settings put system font_scale 2.0")
        runBlocking { BatteryGraph.settings.update { it.copy(dynamicColors = false) } }
        compose.waitUntil(120_000) { compose.activity.resources.configuration.fontScale >= 1.99f }
        awaitNow()
        compose.onNodeWithText(label(R.string.now_start_monitoring)).assertIsDisplayed()
        capture("now-dark-font200")
        scroll(R.string.now_apps_title); capture("now-cards-dark-font200")
        tab(TestTags.TAB_SETTINGS)
        compose.onNodeWithContentDescription(label(R.string.settings_more)).performClick()
        click(R.string.reset_settings_desc)
        compose.onNodeWithText(label(R.string.reset_all_settings)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.reset_all)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.cancel)).assertIsDisplayed()
        val resetVisible = compose.onNodeWithText(label(R.string.reset_ui)).fetchSemanticsNode().boundsInRoot
        val resetAll = compose.onNodeWithText(label(R.string.reset_all)).fetchSemanticsNode().boundsInRoot
        val cancel = compose.onNodeWithText(label(R.string.cancel)).fetchSemanticsNode().boundsInRoot
        Assert.assertTrue("Reset actions must not overlap at200% font", resetVisible.bottom <= resetAll.top && resetAll.bottom <= cancel.top)
        capture("settings-reset-dark-font200")
        click(R.string.cancel) // Inspect the destructive control without resetting preferences.
        backToNow()
        changedOrientation = true
        DeviceEnvironment.device.setOrientationLeft()
        compose.waitUntil(120_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        awaitNow()
        capture("now-dark-font200-landscape")
        scroll(R.string.now_apps_title)
        capture("now-cards-dark-font200-landscape")
    }

    @Test fun tabsAreReachableBackReturnsToNowAndRetapPopsToRoot() {
        compose.waitUntil(120_000) { BatteryGraph.repo.realtimeFlow.value.level != null }
        awaitNow()

        // Each tab opens its screen directly (no push); back from a tab root returns to Now.
        tab(TestTags.TAB_APPS)
        compose.onNodeWithText(label(R.string.adv_access_help)).assertIsDisplayed()
        backToNow()

        tab(TestTags.TAB_HISTORY)
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        backToNow()

        tab(TestTags.TAB_SETTINGS)
        compose.onNodeWithContentDescription(label(R.string.settings_more)).assertIsDisplayed()
        backToNow()

        // Now's Health panel pushes Health onto the Now tab; re-tapping Now pops back to its root.
        scroll(R.string.now_health_title); click(R.string.now_health_title)
        compose.waitUntil(120_000) { !nowShowing() }
        compose.onNodeWithText(label(R.string.battery_health)).assertIsDisplayed()
        tab(TestTags.TAB_NOW)
        awaitNow()
    }

    private companion object {
        /** A literal in the interim BatterySettingsContent (replaced in P4b); it opens Settings › Data. */
        const val EXPORT_DATA_ROW = "Export Battery Data"
    }
}
