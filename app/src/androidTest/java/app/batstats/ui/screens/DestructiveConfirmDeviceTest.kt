package app.batstats.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.batstats.R
import app.batstats.battery.util.ShellRunner
import app.batstats.settings.AppSettings
import app.batstats.test.DeviceEnvironment
import app.batstats.ui.theme.MainTheme
import io.github.mlmgames.settings.core.backup.ImportResult
import io.github.mlmgames.settings.core.resources.AndroidStringResourceProvider
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Stats reset and "Clear All Data" may only run after their confirmation dialog; a failed clear keeps the dialog open. */
@RunWith(AndroidJUnit4::class)
class DestructiveConfirmDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val snackbarHost = SnackbarHostState()
    private fun label(id: Int) = DeviceEnvironment.context.getString(id)

    private fun showDetailedStats(
        mode: () -> ShellRunner.Mode = { ShellRunner.Mode.ROOT },
        refreshing: Boolean = false,
        onResetStats: () -> Unit = {},
    ) = compose.setContent {
        MainTheme(darkTheme = false) {
            DetailedStatsContent(
                state = DetailedStatsUiState(
                    snapshot = null,
                    deviceIdle = null,
                    powerManager = null,
                    refreshing = refreshing,
                    error = null,
                    mode = mode(),
                    shizukuRunning = false,
                    shizukuAuthorized = false,
                    adbCommands = "",
                ),
                tab = 0,
                onTabSelected = {},
                onBack = {},
                onRefresh = {},
                onRequestShizukuPermission = {},
                onCopyAdbCommands = {},
                onResetStats = onResetStats,
                kernelContent = {},
            )
        }
    }

    private fun openClearDataDialog(onClearHistory: suspend () -> Unit) {
        compose.setContent {
            val context = LocalContext.current
            MainTheme(darkTheme = false) {
                BatterySettingsContent(
                    settings = AppSettings(),
                    settingsError = null,
                    stringProvider = remember(context) { AndroidStringResourceProvider(context) },
                    onBack = {},
                    onExportData = {},
                    onExportSettings = {},
                    onOpenAlertSettings = {},
                    onUpdateSetting = { _, _ -> },
                    onResetUiSettings = { true },
                    onResetAll = { true },
                    onImportSettings = { ImportResult.Success(0, 0, emptyList()) },
                    onClearHistory = onClearHistory,
                    snackbarHostState = snackbarHost,
                )
            }
        }
        // The settings row title is a literal in BatterySettingsContent; the dialog title is R.string.clear_all_data.
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(CLEAR_ALL_DATA_ROW))
        compose.onNodeWithText(CLEAR_ALL_DATA_ROW).performClick()
        compose.onNodeWithText(label(R.string.history_clear_warning)).assertIsDisplayed()
    }

    private fun awaitText(id: Int) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(label(id)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun resetStatsRunsOnlyAfterConfirmation() {
        var resets = 0
        showDetailedStats(onResetStats = { resets++ })
        compose.onNodeWithText(label(R.string.adv_reset_android)).assertIsEnabled().performClick()
        compose.onNodeWithText(label(R.string.adv_reset_warning)).assertIsDisplayed()
        assertEquals(0, resets)
        compose.onNodeWithText(label(R.string.adv_reset_confirm)).performClick()
        compose.waitForIdle()
        assertEquals(1, resets)
        compose.onAllNodesWithText(label(R.string.adv_reset_warning)).assertCountEquals(0)
    }

    @Test fun cancellingResetStatsDoesNothing() {
        var resets = 0
        showDetailedStats(onResetStats = { resets++ })
        compose.onNodeWithText(label(R.string.adv_reset_android)).performClick()
        compose.onNodeWithText(label(R.string.adv_cancel)).performClick()
        compose.waitForIdle()
        assertEquals(0, resets)
        compose.onAllNodesWithText(label(R.string.adv_reset_warning)).assertCountEquals(0)
    }

    @Test fun resetStatsEnabledExactlyWithAdvancedAccess() {
        var mode by mutableStateOf(ShellRunner.Mode.ROOT)
        showDetailedStats(mode = { mode })
        ShellRunner.Mode.entries.forEach { candidate ->
            mode = candidate
            compose.waitForIdle()
            val button = compose.onNodeWithText(label(R.string.adv_reset_android))
            if (candidate == ShellRunner.Mode.NONE) button.assertIsNotEnabled() else button.assertIsEnabled()
        }
    }

    @Test fun resetStatsDisabledWhileRefreshing() {
        showDetailedStats(refreshing = true)
        compose.onNodeWithText(label(R.string.adv_reset_android)).assertIsNotEnabled()
    }

    @Test fun clearDataRunsOnlyAfterConfirmation() {
        var clears = 0
        openClearDataDialog(onClearHistory = { clears++ })
        assertEquals(0, clears)
        compose.onNodeWithText(label(R.string.delete_all)).performClick()
        awaitText(R.string.data_cleared)
        assertEquals(1, clears)
        compose.onAllNodesWithText(label(R.string.history_clear_warning)).assertCountEquals(0)
    }

    @Test fun cancellingClearDataDoesNothing() {
        var clears = 0
        openClearDataDialog(onClearHistory = { clears++ })
        compose.onNodeWithText(label(R.string.cancel)).performClick()
        compose.waitForIdle()
        assertEquals(0, clears)
        compose.onAllNodesWithText(label(R.string.history_clear_warning)).assertCountEquals(0)
    }

    @Test fun clearDataLocksTheDialogWhileRunning() {
        val finish = CompletableDeferred<Unit>()
        var clears = 0
        openClearDataDialog(onClearHistory = {
            clears++
            finish.await()
        })
        compose.onNodeWithText(label(R.string.delete_all)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.delete_all)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.cancel)).assertIsNotEnabled()
        DeviceEnvironment.device.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.history_clear_warning)).assertIsDisplayed()
        finish.complete(Unit)
        awaitText(R.string.data_cleared)
        assertEquals(1, clears)
        compose.onAllNodesWithText(label(R.string.history_clear_warning)).assertCountEquals(0)
    }

    @Test fun failedClearDataKeepsDialogOpenAndCanBeRetried() {
        var clears = 0
        openClearDataDialog(onClearHistory = {
            clears++
            if (clears == 1) throw IllegalStateException("simulated storage failure")
        })
        compose.onNodeWithText(label(R.string.delete_all)).performClick()
        awaitText(R.string.history_clear_failed)
        assertEquals(1, clears)
        compose.onNodeWithText(label(R.string.history_clear_warning)).assertIsDisplayed()
        // The buttons re-enable only once the failure snackbar is gone: `clearingHistory = false`
        // runs after the suspending showSnackbar call. Dismiss it instead of waiting out its timeout.
        compose.runOnIdle { snackbarHost.currentSnackbarData?.dismiss() }
        compose.onNodeWithText(label(R.string.delete_all)).assertIsEnabled().performClick()
        awaitText(R.string.data_cleared)
        assertEquals(2, clears)
        compose.onAllNodesWithText(label(R.string.history_clear_warning)).assertCountEquals(0)
    }

    private companion object {
        const val CLEAR_ALL_DATA_ROW = "Clear All Data"
    }
}
