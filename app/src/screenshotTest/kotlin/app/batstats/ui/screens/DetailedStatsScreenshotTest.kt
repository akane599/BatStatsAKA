package app.batstats.ui.screens

import androidx.compose.runtime.Composable
import app.batstats.battery.util.BatteryStatsParser
import app.batstats.battery.util.KernelStats
import app.batstats.battery.util.ShellRunner
import app.batstats.ui.FIXED_TIME_MS
import app.batstats.ui.PhonePreview
import app.batstats.ui.ScreenPreviews
import app.batstats.ui.ScreenshotTheme
import com.android.tools.screenshot.PreviewTest

private const val MINUTE_MS = 60 * 1000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val OVERVIEW_TAB = 0
private const val KERNEL_TAB_INDEX = 6

private val populatedSnapshot = BatteryStatsParser.FullSnapshot(
    capturedAt = FIXED_TIME_MS,
    startedAt = FIXED_TIME_MS - 14 * HOUR_MS - 20 * MINUTE_MS,
    startCount = 37,
    batteryRealtimeMs = 14 * HOUR_MS + 20 * MINUTE_MS,
    batteryUptimeMs = 4 * HOUR_MS + 55 * MINUTE_MS,
    screenOnTimeMs = 3 * HOUR_MS + 25 * MINUTE_MS,
    screenOffTimeMs = 10 * HOUR_MS + 55 * MINUTE_MS,
    screenDozeTimeMs = 2 * HOUR_MS + 10 * MINUTE_MS,
    screenOffDischargePercent = 11,
    screenOnDischargePercent = 27,
    estimatedCapacityMah = 4_512.0,
    componentEstimatesMah = linkedMapOf(
        "screen" to 612.4,
        "cpu" to 388.1,
        "cellular" to 142.7,
        "wifi" to 58.3,
        "bluetooth" to 6.9,
    ),
    signalStrength = listOf(
        BatteryStatsParser.SignalStrengthStats(level = 2, durationMs = 3 * HOUR_MS + 5 * MINUTE_MS, percentOfTotal = 0.21f),
        BatteryStatsParser.SignalStrengthStats(level = 3, durationMs = 7 * HOUR_MS + 40 * MINUTE_MS, percentOfTotal = 0.54f),
        BatteryStatsParser.SignalStrengthStats(level = 4, durationMs = 3 * HOUR_MS + 35 * MINUTE_MS, percentOfTotal = 0.25f),
    ),
    wifiSignal = listOf(
        BatteryStatsParser.WifiSignalStats(level = 3, durationMs = 5 * HOUR_MS + 10 * MINUTE_MS, percentOfTotal = 0.36f),
        BatteryStatsParser.WifiSignalStats(level = 4, durationMs = 9 * HOUR_MS + 10 * MINUTE_MS, percentOfTotal = 0.64f),
    ),
    bluetooth = BatteryStatsParser.BluetoothStats(
        idleTimeMs = 13 * HOUR_MS + 50 * MINUTE_MS,
        rxTimeMs = 12 * MINUTE_MS,
        txTimeMs = 4 * MINUTE_MS,
        powerMah = 6.9,
    ),
    doze = BatteryStatsParser.DozeStats(
        idleModeTimeMs = 6 * HOUR_MS + 30 * MINUTE_MS,
        idleModeCount = 14,
        deepIdleTimeMs = 5 * HOUR_MS + 45 * MINUTE_MS,
        deepIdleCount = 9,
        lightIdleTimeMs = 2 * HOUR_MS + 5 * MINUTE_MS,
        lightIdleCount = 31,
    ),
)

private val populatedState = DetailedStatsUiState(
    snapshot = populatedSnapshot,
    deviceIdle = BatteryStatsParser.DeviceIdleInfo(
        currentState = "ACTIVE",
        lightState = "ACTIVE",
        deepEnabled = true,
        lightEnabled = true,
        screenOnTime = null,
        screenOffTime = null,
        whitelistedApps = listOf("com.google.android.gms"),
        tempWhitelistedApps = emptyList(),
    ),
    powerManager = BatteryStatsParser.PowerManagerInfo(
        screenBrightness = 0.42,
        isScreenOn = true,
        holdingWakeLocks = emptyList(),
        suspendBlockers = emptyList(),
        batteryLevel = 62,
        batteryStatus = "Discharging",
        lowPowerMode = false,
        deviceIdleMode = "ACTIVE",
    ),
    refreshing = false,
    error = null,
    mode = ShellRunner.Mode.ROOT,
    shizukuRunning = false,
    shizukuAuthorized = false,
    adbCommands = listOf(
        "adb shell pm grant org.mlm.batstats android.permission.DUMP",
        "adb shell pm grant org.mlm.batstats android.permission.PACKAGE_USAGE_STATS",
        "adb shell appops set org.mlm.batstats GET_USAGE_STATS allow",
    ).joinToString("\n"),
)

private val populatedKernel = KernelDetailsState(
    root = true,
    battery = KernelStats.Battery(
        capturedAt = FIXED_TIME_MS,
        source = "/sys/class/power_supply/battery/uevent",
        technology = "Li-poly",
        cycleCount = 412,
        chargeFullDesign = 4_500_000,
        chargeFull = 4_068_000,
        chargeNow = 2_522_000,
        currentNow = -412_000,
        voltageNow = 3_912_000,
        tempNow = 312,
        health = "Good",
        status = "Discharging",
        capacityLevel = "Normal",
        timeToEmptyNow = 6 * 60 * 60L + 7 * 60L,
        timeToFullNow = null,
        batteryAge = 90.4,
    ),
    cpu = listOf(
        KernelStats.Cpu(
            cluster = 0,
            currentFreq = 1_171_200,
            minFreq = 300_000,
            maxFreq = 1_804_800,
            governor = "schedutil",
            timeInState = linkedMapOf(300_000L to 182_340L, 1_171_200L to 40_210L, 1_804_800L to 9_870L),
        ),
        KernelStats.Cpu(
            cluster = 4,
            currentFreq = 710_400,
            minFreq = 710_400,
            maxFreq = 2_419_200,
            governor = "schedutil",
            timeInState = linkedMapOf(710_400L to 201_550L, 1_555_200L to 18_320L, 2_419_200L to 3_410L),
        ),
    ),
    thermal = listOf(
        KernelStats.Thermal(
            name = "thermal_zone0",
            type = "battery",
            tempMilliC = 31_200,
            tripPoints = listOf(KernelStats.Trip(type = "passive", tempMilliC = 45_000)),
        ),
    ),
    wakelocks = listOf(
        KernelStats.Wakelock(
            name = "PowerManagerService.WakeLocks",
            count = 1_204,
            wakeCount = 0,
            expireCount = 0,
            totalTimeMs = 38 * MINUTE_MS,
            maxTimeMs = 4 * MINUTE_MS,
            source = "/sys/kernel/debug/wakeup_sources",
        ),
    ),
    busy = false,
    errors = emptyMap(),
    collectedAt = linkedMapOf(
        "CPU" to FIXED_TIME_MS - 2 * MINUTE_MS,
        "Thermal" to FIXED_TIME_MS - 2 * MINUTE_MS,
        "Wake sources" to FIXED_TIME_MS - 2 * MINUTE_MS,
    ),
)

private val noAccessState = populatedState.copy(
    snapshot = null,
    deviceIdle = null,
    powerManager = null,
    mode = ShellRunner.Mode.NONE,
    shizukuRunning = true,
    shizukuAuthorized = false,
)

private val noRootKernel = KernelDetailsState(root = false, battery = null)

private val loadingState = noAccessState.copy(
    refreshing = true,
    mode = ShellRunner.Mode.SHIZUKU,
    shizukuAuthorized = true,
)

@Composable
private fun DetailedStatsPreviewContent(state: DetailedStatsUiState, kernel: KernelDetailsState, tab: Int) {
    DetailedStatsContent(
        state = state,
        tab = tab,
        onTabSelected = {},
        onBack = {},
        onRefresh = {},
        onRequestShizukuPermission = {},
        onCopyAdbCommands = {},
        onResetStats = {},
        kernelContent = { KernelDetails(state = kernel, refresh = {}) },
    )
}

@PreviewTest
@ScreenPreviews
@Composable
fun DetailedStatsScreenPreview() {
    ScreenshotTheme {
        DetailedStatsPreviewContent(populatedState, populatedKernel, OVERVIEW_TAB)
    }
}

@PreviewTest
@PhonePreview
@Composable
fun DetailedStatsScreenOledPreview() {
    ScreenshotTheme(oledBlack = true) {
        DetailedStatsPreviewContent(populatedState, populatedKernel, OVERVIEW_TAB)
    }
}

@PreviewTest
@PhonePreview
@Composable
fun DetailedStatsKernelTabPreview() {
    ScreenshotTheme {
        DetailedStatsPreviewContent(populatedState, populatedKernel, KERNEL_TAB_INDEX)
    }
}

@PreviewTest
@PhonePreview
@Composable
fun DetailedStatsNoAccessPreview() {
    ScreenshotTheme {
        DetailedStatsPreviewContent(noAccessState, noRootKernel, KERNEL_TAB_INDEX)
    }
}

@PreviewTest
@PhonePreview
@Composable
fun DetailedStatsLoadingPreview() {
    ScreenshotTheme {
        DetailedStatsPreviewContent(loadingState, noRootKernel, OVERVIEW_TAB)
    }
}
