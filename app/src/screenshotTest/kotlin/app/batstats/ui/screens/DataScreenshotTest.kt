package app.batstats.ui.screens

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.batstats.ui.PhonePreview
import app.batstats.ui.ScreenPreviews
import app.batstats.ui.ScreenshotTheme
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@ScreenPreviews
@Composable
fun DataScreenPreview() {
    ScreenshotTheme {
        DataContent(
            isBusy = false,
            days = 0,
            includeSamples = true,
            includeSessions = true,
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onDaysChange = {},
            onToggleSamples = {},
            onToggleSessions = {},
            onExportJson = {},
            onExportCsv = {},
            onImportJson = {},
            onImportCsv = {},
        )
    }
}

@PreviewTest
@PhonePreview
@Composable
fun DataScreenOledPreview() {
    ScreenshotTheme(oledBlack = true) {
        DataContent(
            isBusy = false,
            days = 0,
            includeSamples = true,
            includeSessions = true,
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onDaysChange = {},
            onToggleSamples = {},
            onToggleSessions = {},
            onExportJson = {},
            onExportCsv = {},
            onImportJson = {},
            onImportCsv = {},
        )
    }
}

@PreviewTest
@PhonePreview
@Composable
fun DataScreenBusyPreview() {
    ScreenshotTheme {
        DataContent(
            isBusy = true,
            days = 7,
            includeSamples = true,
            includeSessions = false,
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onDaysChange = {},
            onToggleSamples = {},
            onToggleSessions = {},
            onExportJson = {},
            onExportCsv = {},
            onImportJson = {},
            onImportCsv = {},
        )
    }
}
