package app.batstats.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.batstats.R
import app.batstats.battery.util.ShellRunner
import app.batstats.test.DeviceEnvironment
import app.batstats.ui.theme.MainTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stats reset may only run after its confirmation dialog. The "Clear all data" cases moved to Settings › Data
 * (P4b Data agent's rewrite of this file).
 */
@RunWith(AndroidJUnit4::class)
class DestructiveConfirmDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun label(id: Int) = DeviceEnvironment.context.getString(id)

    private fun showDetailedStats(
        mode: () -> ShellRunner.Mode = { ShellRunner.Mode.ROOT },
        refreshing: Boolean = false,
        onResetStats: () -> Unit = {},
    ) = compose.setContent {
        MainTheme {
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
}
