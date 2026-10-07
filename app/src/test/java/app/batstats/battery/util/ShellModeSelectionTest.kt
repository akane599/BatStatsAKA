package app.batstats.battery.util

import app.batstats.battery.util.ShellRunner.Mode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ShellModeSelectionTest {
    @Test fun runningAuthorizedShizukuWinsWithoutProbingRootOrAdb() = runTest {
        assertEquals(
            Mode.SHIZUKU,
            selectShellMode(
                shizukuRunning = { true },
                shizukuAuthorized = { true },
                rootAvailable = { error("Root must not be probed") },
                adbAvailable = { error("ADB must not be probed") },
            ),
        )
    }

    @Test fun runningDeniedShizukuFallsBackToRootBeforeAdb() = runTest {
        assertEquals(
            Mode.ROOT,
            selectShellMode(
                shizukuRunning = { true },
                shizukuAuthorized = { false },
                rootAvailable = { true },
                adbAvailable = { error("ADB must not be probed when root is available") },
            ),
        )
    }

    @Test fun runningDeniedShizukuFallsBackToAdbWhenRootIsUnavailable() = runTest {
        val probes = mutableListOf<String>()
        assertEquals(
            Mode.ADB,
            selectShellMode(
                shizukuRunning = { true },
                shizukuAuthorized = { false },
                rootAvailable = { probes += "root"; false },
                adbAvailable = { probes += "adb"; true },
            ),
        )
        assertEquals(listOf("root", "adb"), probes)
    }

    @Test fun stoppedShizukuFallsBackToRootWithoutCheckingShizukuPermission() = runTest {
        assertEquals(
            Mode.ROOT,
            selectShellMode(
                shizukuRunning = { false },
                shizukuAuthorized = { error("Stopped Shizuku must not be checked for permission") },
                rootAvailable = { true },
                adbAvailable = { error("ADB must not be probed when root is available") },
            ),
        )
    }

    @Test fun stoppedShizukuFallsBackToAdbWhenRootIsUnavailable() = runTest {
        assertEquals(
            Mode.ADB,
            selectShellMode(
                shizukuRunning = { false },
                shizukuAuthorized = { true },
                rootAvailable = { false },
                adbAvailable = { true },
            ),
        )
    }

    @Test fun deniedShizukuWithNoOtherBackendReturnsNone() = runTest {
        assertEquals(
            Mode.NONE,
            selectShellMode(
                shizukuRunning = { true },
                shizukuAuthorized = { false },
                rootAvailable = { false },
                adbAvailable = { false },
            ),
        )
    }

    @Test fun noAvailableBackendReturnsNone() = runTest {
        assertEquals(
            Mode.NONE,
            selectShellMode(
                shizukuRunning = { false },
                shizukuAuthorized = { false },
                rootAvailable = { false },
                adbAvailable = { false },
            ),
        )
    }
}
