package app.batstats.ui.screens

import androidx.compose.runtime.Composable
import app.batstats.battery.apps.AppLabel
import app.batstats.ui.FIXED_TIME_MS
import app.batstats.ui.ScreenPreviews
import app.batstats.ui.ScreenshotTheme
import app.batstats.ui.TallPhonePreview
import app.batstats.viewmodel.AlarmItem
import app.batstats.viewmodel.AppDetailsUiState
import app.batstats.viewmodel.AppHistory
import app.batstats.viewmodel.AppHistoryState
import app.batstats.viewmodel.AppSessionUsage
import app.batstats.viewmodel.AppUsageDetails
import app.batstats.viewmodel.HardwareUsage
import app.batstats.viewmodel.NetworkUsage
import app.batstats.viewmodel.TaskItem
import app.batstats.viewmodel.WakelockItem
import app.batstats.viewmodel.WakelockKind
import com.android.tools.screenshot.PreviewTest

// Times render in the suite's pinned UTC/en-US; the dump was read 11 min before FIXED_TIME_MS (09:20).
private const val SECOND = 1_000L
private const val MINUTE = 60 * SECOND
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

/** Twelve sessions on battery over two weeks; the app wasn't in the top 30 of three of them. */
private val history = AppHistory(
    listOf(18.2, 42.0, null, 31.5, 64.8, 12.1, null, 88.4, 40.2, 27.9, null, 55.0).mapIndexed { i, mah ->
        AppSessionUsage("s$i", FIXED_TIME_MS - (13 - i) * DAY - 8 * HOUR, mah)
    },
)

private fun full() = AppDetailsUiState(
    uid = 10_201,
    packageName = "com.google.android.youtube",
    nowMs = FIXED_TIME_MS,
    label = AppLabel.Named("YouTube"),
    canOpenAppInfo = true,
    capturedAtMs = FIXED_TIME_MS - 11 * MINUTE,
    startedAtMs = FIXED_TIME_MS - 14 * HOUR - 20 * MINUTE,
    usage = AppUsageDetails(
        powerMah = 58.2,
        share = 0.095f,
        foregroundMs = HOUR + 12 * MINUTE,
        foregroundServiceMs = 38 * MINUTE,
        backgroundMs = 21 * MINUTE,
        cachedMs = 4 * HOUR + 5 * MINUTE,
        cpuTimeMs = 14 * MINUTE,
        wakelockTimeMs = 9 * MINUTE,
        wakelocks = listOf(
            WakelockItem("*job*/com.google.android.youtube/.offline.OfflineRefreshService", WakelockKind.CPU, 6 * MINUTE, 41),
            WakelockItem("ExoPlayer:WakeLockManager", WakelockKind.CPU, 2 * MINUTE, 7),
            WakelockItem("*alarm*:com.google.android.youtube.NOTIFICATION_REFRESH", WakelockKind.CPU, 40 * SECOND, 18),
            WakelockItem("YouTube video", WakelockKind.SCREEN, 25 * SECOND, 3),
            WakelockItem("GCM_CONN_ALARM", WakelockKind.CPU, 12 * SECOND, 22),
            WakelockItem("*sync*/com.google.android.youtube", WakelockKind.CPU, 4 * SECOND, 2),
            WakelockItem("NetworkStats", WakelockKind.CPU, 2 * SECOND, 5),
        ),
        alarms = listOf(
            AlarmItem("*walarm*:com.google.android.youtube.NOTIFICATION_REFRESH", 18),
            AlarmItem("*walarm*:com.google.android.libraries.youtube.innertube.PING", 4),
        ),
        jobs = listOf(
            TaskItem("com.google.android.youtube/.offline.OfflineRefreshService", 12, 5 * MINUTE),
            TaskItem("com.google.android.youtube/.notification.NotificationRegistrationJob", 2, 9 * SECOND),
        ),
        syncs = listOf(TaskItem("com.google.android.youtube.provider", 3, 15 * SECOND)),
        network = NetworkUsage(
            mobileRxBytes = 412_500_000,
            mobileTxBytes = 8_300_000,
            wifiRxBytes = 1_240_000_000,
            wifiTxBytes = 22_100_000,
            radioActiveMs = 26 * MINUTE,
        ),
        hardware = HardwareUsage(audioMs = HOUR + 40 * MINUTE, videoMs = HOUR + 2 * MINUTE, sensorsMs = 3 * MINUTE),
    ),
    history = AppHistoryState.Loaded(history),
)

/** A quiet app: a little battery and foreground time, nothing in the background, no hardware, no history yet. */
private fun sparse() = AppDetailsUiState(
    uid = 10_212,
    packageName = "com.android.calculator2",
    nowMs = FIXED_TIME_MS,
    label = AppLabel.Named("Calculator"),
    canOpenAppInfo = true,
    capturedAtMs = FIXED_TIME_MS - 11 * MINUTE,
    startedAtMs = FIXED_TIME_MS - 14 * HOUR - 20 * MINUTE,
    usage = AppUsageDetails(
        powerMah = 0.4,
        share = 0.0007f,
        foregroundMs = 3 * MINUTE,
        cachedMs = 50 * MINUTE,
        cpuTimeMs = 4 * SECOND,
    ),
    history = AppHistoryState.Loaded(AppHistory(emptyList())),
)

@Composable
private fun AppDetailsPreviewContent(state: AppDetailsUiState) {
    AppDetailsContent(state = state, onEvent = {})
}

@PreviewTest
@ScreenPreviews
@Composable
fun AppDetailsScreenPreview() {
    ScreenshotTheme { AppDetailsPreviewContent(full()) }
}

@PreviewTest
@TallPhonePreview
@Composable
fun AppDetailsSparsePreview() {
    ScreenshotTheme { AppDetailsPreviewContent(sparse()) }
}

/** The quiet app when its stored sessions couldn't be read: an error with Try again, not "no sessions". */
@PreviewTest
@TallPhonePreview
@Composable
fun AppDetailsHistoryFailedPreview() {
    ScreenshotTheme { AppDetailsPreviewContent(sparse().copy(history = AppHistoryState.Failed)) }
}

/** The whole page of the full state on a tall phone (the lists, network, hardware and history below the fold). */
@PreviewTest
@TallPhonePreview
@Composable
fun AppDetailsFullTallPreview() {
    ScreenshotTheme { AppDetailsPreviewContent(full()) }
}
