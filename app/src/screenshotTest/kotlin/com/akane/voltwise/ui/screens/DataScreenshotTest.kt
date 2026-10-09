package com.akane.voltwise.ui.screens

import androidx.compose.runtime.Composable
import com.akane.voltwise.battery.data.HistoryImportResult
import com.akane.voltwise.ui.PhonePreview
import com.akane.voltwise.ui.ScreenPreviews
import com.akane.voltwise.ui.ScreenshotTheme
import com.akane.voltwise.ui.TallPhonePreview
import com.akane.voltwise.viewmodel.ClearStep
import com.akane.voltwise.viewmodel.DataFailure
import com.akane.voltwise.viewmodel.DataOutcome
import com.akane.voltwise.viewmodel.DataTask
import com.akane.voltwise.viewmodel.DataUiState
import com.akane.voltwise.viewmodel.HistoryRange
import com.akane.voltwise.viewmodel.StoredHistory
import com.android.tools.screenshot.PreviewTest

private val stored = StoredHistory(samples = 12_480, sessions = 86)

@Composable
private fun Data(state: DataUiState, oled: Boolean = false) {
    ScreenshotTheme(oledBlack = oled) {
        DataContent(state = state, onEvent = {}, onBack = {})
    }
}

/** Default: history stored, whole period, both tables included, no task run yet. */
@PreviewTest
@ScreenPreviews
@Composable
fun DataScreenPreview() {
    Data(DataUiState(stored = stored))
}

/** An export running: every action waits, the export panel shows progress. */
@PreviewTest
@PhonePreview
@Composable
fun DataScreenBusyPreview() {
    Data(DataUiState(stored = stored, range = HistoryRange.WEEK, includeSessions = false, running = DataTask.EXPORT_JSON))
}

/** An import finished (its counts under the import actions). */
@PreviewTest
@TallPhonePreview
@Composable
fun DataScreenImportedPreview() {
    Data(
        DataUiState(
            stored = StoredHistory(13_720, 89),
            outcome = DataOutcome.HistoryImported(DataTask.IMPORT_JSON, HistoryImportResult(1_240, 3, 2, 5)),
        ),
    )
}

/** An invalid settings file: the error under the settings actions, nothing changed. */
@PreviewTest
@TallPhonePreview
@Composable
fun DataScreenSettingsInvalidPreview() {
    Data(
        DataUiState(
            stored = stored,
            includeSamples = false,
            includeSessions = false,
            outcome = DataOutcome.Failed(DataTask.RESTORE_SETTINGS, DataFailure.SETTINGS_INVALID),
        ),
    )
}

/** A refused history file shows the check's own reason. */
@PreviewTest
@TallPhonePreview
@Composable
fun DataScreenImportRejectedPreview() {
    Data(
        DataUiState(
            stored = stored,
            outcome = DataOutcome.Failed(DataTask.IMPORT_CSV, DataFailure.REJECTED, "Import conflicts with a local observation"),
        ),
    )
}

/** Just cleared: counts at zero, the result under "Clear all data". */
@PreviewTest
@TallPhonePreview
@Composable
fun DataScreenClearedPreview() {
    Data(DataUiState(stored = StoredHistory(0, 0), outcome = DataOutcome.Cleared), oled = true)
}

/** The confirmation. */
@PreviewTest
@PhonePreview
@Composable
fun DataScreenClearConfirmPreview() {
    Data(DataUiState(stored = stored, clear = ClearStep.CONFIRM))
}

/** A failed clear keeps the dialog open with the error, ready to retry. */
@PreviewTest
@PhonePreview
@Composable
fun DataScreenClearFailedPreview() {
    Data(DataUiState(stored = stored, clear = ClearStep.FAILED))
}
