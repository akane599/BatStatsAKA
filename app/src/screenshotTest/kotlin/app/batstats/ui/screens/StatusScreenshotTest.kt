package app.batstats.ui.screens

import androidx.compose.runtime.Composable
import app.batstats.battery.measurement.CalibrationSource
import app.batstats.battery.measurement.CurrentCalibration
import app.batstats.battery.measurement.CurrentSign
import app.batstats.battery.measurement.CurrentUnit
import app.batstats.ui.FIXED_TIME_MS
import app.batstats.ui.PhonePreview
import app.batstats.ui.ScreenPreviews
import app.batstats.ui.ScreenshotTheme
import app.batstats.ui.TallPhonePreview
import app.batstats.viewmodel.AccessMode
import app.batstats.viewmodel.AccessState
import app.batstats.viewmodel.CalibrationStatus
import app.batstats.viewmodel.ShizukuState
import app.batstats.viewmodel.StatusIssue
import app.batstats.viewmodel.StatusIssueKind
import app.batstats.viewmodel.StatusUiState
import com.android.tools.screenshot.PreviewTest

// Times render in the suite's pinned UTC/en-US; "now" is FIXED_TIME_MS (Oct 9, 09:20).
private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

private val commands = listOf(
    "adb shell pm grant com.akane.voltwise android.permission.DUMP",
    "adb shell pm grant com.akane.voltwise android.permission.PACKAGE_USAGE_STATS",
    "adb shell appops set com.akane.voltwise GET_USAGE_STATS allow",
)

private val issues = listOf(
    StatusIssue(StatusIssueKind.ADVANCED_READ_FAILED, FIXED_TIME_MS - 40 * MINUTE, FIXED_TIME_MS - 40 * MINUTE, 1),
    StatusIssue(StatusIssueKind.OBSERVATION_GAP, FIXED_TIME_MS - 5 * HOUR, FIXED_TIME_MS - 3 * HOUR, 3),
    StatusIssue(StatusIssueKind.CHARGE_UNAVAILABLE, FIXED_TIME_MS - 30 * HOUR, FIXED_TIME_MS - 26 * HOUR, 1),
)

private val detectedMa = CalibrationStatus(CurrentUnit.MILLIAMPS, CurrentSign.NORMAL, CalibrationSource.DETECTED)

@Composable
private fun Status(state: StatusUiState, oled: Boolean = false) {
    ScreenshotTheme(oledBlack = oled) {
        StatusContent(state = state, onEvent = {}, onBack = {})
    }
}

/** Default: no advanced access (setup steps open), a detected mA calibration, a few recent issues. */
@PreviewTest
@ScreenPreviews
@Composable
fun StatusScreenPreview() {
    Status(
        StatusUiState(
            access = AccessState(AccessMode.NONE, adbCommands = commands),
            calibration = detectedMa,
            issues = issues,
            nowMs = FIXED_TIME_MS,
        ),
    )
}

/** Shizuku in use (setup folded away), Android's standard calibration, nothing went wrong. */
@PreviewTest
@TallPhonePreview
@Composable
fun StatusScreenShizukuPreview() {
    Status(
        StatusUiState(
            access = AccessState(AccessMode.SHIZUKU, shizuku = ShizukuState(running = true, granted = true), adbCommands = commands),
            calibration = CalibrationStatus(),
            nowMs = FIXED_TIME_MS,
        ),
    )
}

/** A detected correction waiting for Undo/Keep, first on the page (as on Now). */
@PreviewTest
@PhonePreview
@Composable
fun StatusScreenNoticePreview() {
    val corrected = CurrentCalibration(CurrentUnit.MILLIAMPS, CurrentSign.INVERTED)
    Status(
        StatusUiState(
            access = AccessState(AccessMode.ROOT, adbCommands = commands),
            calibration = CalibrationStatus(corrected.unit, corrected.sign, CalibrationSource.DETECTED, notice = corrected),
            nowMs = FIXED_TIME_MS,
        ),
    )
}

/** Shizuku running but not authorized, and ADB grants on Android 16 (per-app statistics refused). */
@PreviewTest
@TallPhonePreview
@Composable
fun StatusScreenAuthorizePreview() {
    Status(
        StatusUiState(
            access = AccessState(
                AccessMode.NONE,
                shizuku = ShizukuState(running = true, granted = false),
                adbCommands = commands,
                adbCoversAppStats = false,
            ),
            calibration = CalibrationStatus(CurrentUnit.MICROAMPS, CurrentSign.INVERTED, CalibrationSource.OVERRIDE),
            nowMs = FIXED_TIME_MS,
        ),
    )
}

/** Shizuku running with BatStats blocked there ("Deny and don't ask again"): the way back instead of Authorize. */
@PreviewTest
@PhonePreview
@Composable
fun StatusScreenShizukuBlockedPreview() {
    Status(
        StatusUiState(
            access = AccessState(
                AccessMode.NONE,
                shizuku = ShizukuState(running = true, granted = false, blocked = true),
                adbCommands = commands,
            ),
            nowMs = FIXED_TIME_MS,
        ),
    )
}

/** ADB grants in use on Android 16, many issues (folded to five), the log unsaved and sharing unavailable. */
@PreviewTest
@TallPhonePreview
@Composable
fun StatusScreenIssuesPreview() {
    val many = issues + listOf(
        StatusIssue(StatusIssueKind.HISTORY_WRITE_FAILED, FIXED_TIME_MS - 50 * HOUR, FIXED_TIME_MS - 50 * HOUR, 1),
        StatusIssue(StatusIssueKind.NOTIFICATION_FAILED, FIXED_TIME_MS - 60 * HOUR, FIXED_TIME_MS - 58 * HOUR, 12),
        StatusIssue(StatusIssueKind.START_FAILED, FIXED_TIME_MS - 70 * HOUR, FIXED_TIME_MS - 70 * HOUR, 1),
    )
    Status(
        StatusUiState(
            access = AccessState(AccessMode.ADB, adbCommands = commands, adbCoversAppStats = false),
            calibration = detectedMa,
            issues = many,
            issueLogUnavailable = true,
            shareUnavailable = true,
            nowMs = FIXED_TIME_MS,
        ),
        oled = true,
    )
}

/** First open: the access probe still running. */
@PreviewTest
@PhonePreview
@Composable
fun StatusScreenCheckingPreview() {
    Status(StatusUiState(access = AccessState(AccessMode.NONE, checking = true, adbCommands = commands), nowMs = FIXED_TIME_MS))
}
