package app.batstats.ui.screens

import androidx.compose.runtime.Composable
import app.batstats.battery.apps.AppLabel
import app.batstats.ui.FIXED_TIME_MS
import app.batstats.ui.PhonePreview
import app.batstats.ui.ScreenPreviews
import app.batstats.ui.ScreenshotTheme
import app.batstats.ui.TallPhonePreview
import app.batstats.viewmodel.AccessProblem
import app.batstats.viewmodel.AppListRow
import app.batstats.viewmodel.AppSort
import app.batstats.viewmodel.AppsUiState
import app.batstats.viewmodel.ReadProblem
import app.batstats.viewmodel.StatsProblem
import app.batstats.viewmodel.StatsSummary
import com.android.tools.screenshot.PreviewTest

// Times render in the suite's pinned UTC/en-US; the dump was read 11 min before FIXED_TIME_MS (09:20).
private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

private val summary = StatsSummary(
    startedAtMs = FIXED_TIME_MS - 14 * HOUR - 20 * MINUTE,
    capturedAtMs = FIXED_TIME_MS - 11 * MINUTE,
    onBatteryMs = 14 * HOUR + 9 * MINUTE,
    screenOnMs = 3 * HOUR + 25 * MINUTE,
    screenOffMs = 10 * HOUR + 44 * MINUTE,
    screenOnUsedPercent = 27,
    screenOffUsedPercent = 11,
    deepDozeMs = 6 * HOUR + 5 * MINUTE,
    lightDozeMs = 2 * HOUR + 10 * MINUTE,
    capacityMah = 4_512.0,
)

private const val TOTAL_MAH = 612.0

private fun row(uid: Int, packageName: String, label: AppLabel, mah: Double) =
    AppListRow(uid, packageName, label, mah, (mah / TOTAL_MAH).toFloat())

private val apps = listOf(
    row(10_123, "com.android.chrome", AppLabel.Named("Chrome"), 124.0),
    row(10_201, "com.google.android.youtube", AppLabel.Named("YouTube"), 58.2),
    row(10_188, "org.telegram.messenger", AppLabel.Named("Telegram"), 31.6),
    row(10_311, "com.google.android.apps.maps", AppLabel.Named("Maps"), 22.9),
    row(10_402, "com.spotify.music", AppLabel.Named("Spotify"), 14.3),
    row(10_999, "UID 10999", AppLabel.Unknown, 8.1),
    row(10_144, "com.whatsapp", AppLabel.Named("WhatsApp"), 6.4),
    row(10_087, "com.google.android.gm", AppLabel.Named("Gmail"), 2.7),
    row(10_212, "com.android.camera2", AppLabel.Named("Camera"), 0.4),
)

private val systemRows = listOf(
    row(10_050, "com.google.android.gms", AppLabel.Named("Google Play services"), 96.5),
    row(1_000, "android", AppLabel.Named("Android System"), 71.2),
    row(1_041, "System UID 1041", AppLabel.SystemProcess, 12.8),
)

private fun loaded() = AppsUiState(
    nowMs = FIXED_TIME_MS,
    summary = summary,
    rows = apps,
    hiddenSystem = systemRows.size,
)

@Composable
private fun AppsPreviewContent(state: AppsUiState, query: String = state.query) {
    AppsContent(state = state, query = query, onEvent = {})
}

/** A good read: the summary, the controls, and the apps by battery (system components hidden). */
@PreviewTest
@ScreenPreviews
@Composable
fun AppsScreenPreview() {
    ScreenshotTheme { AppsPreviewContent(loaded()) }
}

/** No access yet (ADB grants on Android 16): the banner and the invitation, no list. */
@PreviewTest
@PhonePreview
@Composable
fun AppsNoAccessPreview() {
    ScreenshotTheme {
        AppsPreviewContent(AppsUiState(nowMs = FIXED_TIME_MS, problem = StatsProblem.NoAccess(AccessProblem.ADB_NOT_ENOUGH)))
    }
}

/** Shizuku runs but hasn't allowed BatStats: the banner also offers to ask. */
@PreviewTest
@PhonePreview
@Composable
fun AppsShizukuPreview() {
    ScreenshotTheme {
        AppsPreviewContent(AppsUiState(nowMs = FIXED_TIME_MS, problem = StatsProblem.NoAccess(AccessProblem.SHIZUKU_NOT_ALLOWED)))
    }
}

/** The first read is running: the refresh indicator, the summary's shape and placeholder rows. */
@PreviewTest
@PhonePreview
@Composable
fun AppsLoadingPreview() {
    ScreenshotTheme { AppsPreviewContent(AppsUiState(nowMs = FIXED_TIME_MS, loading = true)) }
}

/** A search with no visible match, where a hidden system component matches: offer to show system apps. */
@PreviewTest
@TallPhonePreview
@Composable
fun AppsEmptySearchPreview() {
    ScreenshotTheme {
        AppsPreviewContent(loaded().copy(rows = emptyList(), hiddenSystem = 1, query = "play services"))
    }
}

/** System apps shown, sorted by background time, with a read failure over the last dump. */
@PreviewTest
@TallPhonePreview
@Composable
fun AppsSystemShownPreview() {
    val minutes = listOf(212.0, 95.0, 64.0, 41.0, 30.0, 18.0, 12.0, 7.0, 3.0, 1.0, 0.5, 0.0)
    val labelled = (systemRows + apps).take(minutes.size)
    val total = minutes.sum()
    ScreenshotTheme {
        AppsPreviewContent(
            loaded().copy(
                problem = StatsProblem.Failed(ReadProblem.SHIZUKU),
                sort = AppSort.BACKGROUND,
                showSystem = true,
                hiddenSystem = 0,
                rows = labelled.zip(minutes) { app, min ->
                    app.copy(value = min * MINUTE, share = (min / total).toFloat())
                },
            ),
        )
    }
}
