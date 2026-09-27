package app.batstats.ui.screens

import androidx.compose.runtime.Composable
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import app.batstats.ui.FIXED_TIME_MS
import app.batstats.ui.PhonePreview
import app.batstats.ui.ScreenPreviews
import app.batstats.ui.ScreenshotTheme
import app.batstats.viewmodel.HistoryViewModel
import com.android.tools.screenshot.PreviewTest

private fun sampleSessions(): List<ChargeSession> = listOf(
    ChargeSession(
        sessionId = "session-1",
        type = SessionType.CHARGE,
        startTime = FIXED_TIME_MS - 3_600_000L,
        endTime = null,
        startLevel = 42,
        endLevel = 68,
        deltaUah = 1_200_000L,
        avgCurrentUa = 850_000L,
        estCapacityMah = null,
        observationId = "obs-1",
        lastSampleTime = FIXED_TIME_MS - 60_000L,
        observedMs = 3_540_000L,
        counterCoveredMs = 3_540_000L,
        screenOnMs = 900_000L,
        screenOffMs = 2_640_000L,
        source = "BatteryManager",
    ),
    ChargeSession(
        sessionId = "session-2",
        type = SessionType.DISCHARGE,
        startTime = FIXED_TIME_MS - 30_000_000L,
        endTime = FIXED_TIME_MS - 25_000_000L,
        startLevel = 95,
        endLevel = 61,
        deltaUah = -2_100_000L,
        avgCurrentUa = -420_000L,
        estCapacityMah = null,
        observationId = "obs-0",
        lastSampleTime = FIXED_TIME_MS - 25_000_000L,
        observedMs = 5_000_000L,
        counterCoveredMs = 5_000_000L,
        screenOnMs = 2_000_000L,
        screenOffMs = 3_000_000L,
        source = "BatteryManager",
    ),
    ChargeSession(
        sessionId = "session-3",
        type = SessionType.PLUGGED,
        startTime = FIXED_TIME_MS - 90_000_000L,
        endTime = FIXED_TIME_MS - 86_000_000L,
        startLevel = 100,
        endLevel = 100,
        deltaUah = 0L,
        avgCurrentUa = 0L,
        estCapacityMah = null,
        observationId = null,
        lastSampleTime = FIXED_TIME_MS - 86_000_000L,
        source = "import:csv",
    ),
)

private fun noOpFilterBy(@Suppress("UNUSED_PARAMETER") type: SessionType?) {}
private fun noOpSearch(@Suppress("UNUSED_PARAMETER") text: String) {}

@PreviewTest
@ScreenPreviews
@Composable
fun HistoryScreenPreview() {
    ScreenshotTheme {
        HistoryContent(
            ui = HistoryViewModel.Ui(sessions = sampleSessions(), loading = false, failed = false, hasMore = true),
            filter = HistoryViewModel.Filter(),
            recording = null,
            onBack = {},
            onOpenSession = {},
            onFilterBy = ::noOpFilterBy,
            onSearch = ::noOpSearch,
            onRetry = {},
            onLoadMore = {},
        )
    }
}

@PreviewTest
@PhonePreview
@Composable
fun HistoryScreenOledPreview() {
    ScreenshotTheme(oledBlack = true) {
        HistoryContent(
            ui = HistoryViewModel.Ui(sessions = sampleSessions(), loading = false, failed = false, hasMore = true),
            filter = HistoryViewModel.Filter(),
            recording = null,
            onBack = {},
            onOpenSession = {},
            onFilterBy = ::noOpFilterBy,
            onSearch = ::noOpSearch,
            onRetry = {},
            onLoadMore = {},
        )
    }
}

@PreviewTest
@PhonePreview
@Composable
fun HistoryScreenEmptyPreview() {
    ScreenshotTheme {
        HistoryContent(
            ui = HistoryViewModel.Ui(sessions = emptyList(), loading = false, failed = false, hasMore = false),
            filter = HistoryViewModel.Filter(),
            recording = null,
            onBack = {},
            onOpenSession = {},
            onFilterBy = ::noOpFilterBy,
            onSearch = ::noOpSearch,
            onRetry = {},
            onLoadMore = {},
        )
    }
}

@PreviewTest
@PhonePreview
@Composable
fun HistoryScreenLoadingPreview() {
    ScreenshotTheme {
        HistoryContent(
            ui = HistoryViewModel.Ui(sessions = emptyList(), loading = true, failed = false, hasMore = false),
            filter = HistoryViewModel.Filter(),
            recording = null,
            onBack = {},
            onOpenSession = {},
            onFilterBy = ::noOpFilterBy,
            onSearch = ::noOpSearch,
            onRetry = {},
            onLoadMore = {},
        )
    }
}

@PreviewTest
@PhonePreview
@Composable
fun HistoryScreenFailedPreview() {
    ScreenshotTheme {
        HistoryContent(
            ui = HistoryViewModel.Ui(sessions = emptyList(), loading = false, failed = true, hasMore = false),
            filter = HistoryViewModel.Filter(),
            recording = null,
            onBack = {},
            onOpenSession = {},
            onFilterBy = ::noOpFilterBy,
            onSearch = ::noOpSearch,
            onRetry = {},
            onLoadMore = {},
        )
    }
}
