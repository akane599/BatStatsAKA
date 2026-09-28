package app.batstats.ui.screens

import androidx.compose.runtime.Composable
import app.batstats.battery.data.db.SessionType
import app.batstats.ui.FIXED_TIME_MS
import app.batstats.ui.PhonePreview
import app.batstats.ui.ScreenPreviews
import app.batstats.ui.ScreenshotTheme
import app.batstats.ui.TallPhonePreview
import app.batstats.viewmodel.AppUsageHint
import app.batstats.viewmodel.DayEntry
import app.batstats.viewmodel.DayFigures
import app.batstats.viewmodel.DayRange
import app.batstats.viewmodel.DaysState
import app.batstats.viewmodel.HistoryMode
import app.batstats.viewmodel.HistoryUiState
import app.batstats.viewmodel.SessionFilter
import app.batstats.viewmodel.SessionRow
import app.batstats.viewmodel.SessionsState
import com.android.tools.screenshot.PreviewTest
import kotlin.math.sin

// Times render in the suite's pinned UTC/en-US; "today" is FIXED_TIME_MS's day (Thu, Oct 9 2025, 09:20).
private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY_MS = 24 * HOUR
private val TODAY = Math.floorDiv(FIXED_TIME_MS, DAY_MS)

/** A plausible day: heavier screen-on drain on some days, a charge most days, one gap (not monitored). */
private fun figures(dayIndex: Int): DayFigures? {
    if (dayIndex % 11 == 4) return null
    val wave = 0.5 + 0.5 * sin(dayIndex * 1.7)
    val onMs = ((1.2 + 3.1 * wave) * HOUR).toLong()
    val offMs = ((9.0 + 4.0 * (1 - wave)) * HOUR).toLong()
    return DayFigures(
        screenOnMs = onMs,
        screenOffMs = offMs,
        screenOnMah = 240.0 + 610 * wave,
        screenOffMah = 70.0 + 95 * (1 - wave) + if (dayIndex % 6 == 2) 260 else 0,
        chargedMah = if (dayIndex % 3 == 1) 0.0 else 1_180.0 + 400 * wave,
        deepSleepPercent = if (dayIndex % 6 == 2) 41.0 else 86.0 + 8 * wave,
    )
}

private fun daysState(range: DayRange): DaysState {
    val days = (TODAY - range.days + 1..TODAY).map { day -> DayEntry(day, figures((TODAY - day).toInt())) }
    // Today is only 9 hours old: a partial day.
    val partial = days.map { entry ->
        if (entry.epochDay != TODAY) entry
        else entry.copy(figures = DayFigures(2 * HOUR + 10 * MINUTE, 5 * HOUR, 412.0, 96.0, 0.0, 88.0))
    }
    return DaysState(
        days = partial,
        average = DayFigures(3 * HOUR + 5 * MINUTE, 11 * HOUR + 20 * MINUTE, 521.0, 168.0, 905.0, 84.0),
        loading = false,
    )
}

private fun sessionRows(): List<SessionRow> = listOf(
    SessionRow("s1", SessionType.DISCHARGE, FIXED_TIME_MS - 2 * HOUR - 8 * MINUTE, FIXED_TIME_MS, TODAY, recording = true, 100, 81, 612.0, null),
    SessionRow("s2", SessionType.CHARGE, FIXED_TIME_MS - 3 * HOUR - 50 * MINUTE, FIXED_TIME_MS - 2 * HOUR - 8 * MINUTE, TODAY, false, 34, 100, 3_140.0, null),
    SessionRow("s3", SessionType.DISCHARGE, FIXED_TIME_MS - 14 * HOUR, FIXED_TIME_MS - 3 * HOUR - 50 * MINUTE, TODAY - 1, false, 92, 34, 2_710.0, AppUsageHint.NO_ACCESS),
    SessionRow("s4", SessionType.PLUGGED, FIXED_TIME_MS - 15 * HOUR, FIXED_TIME_MS - 14 * HOUR, TODAY - 1, false, 92, 92, 0.0, null),
    SessionRow("s5", SessionType.CHARGE, FIXED_TIME_MS - 16 * HOUR - 20 * MINUTE, FIXED_TIME_MS - 15 * HOUR, TODAY - 1, false, 58, 92, 1_690.0, null),
    SessionRow("s6", SessionType.DISCHARGE, FIXED_TIME_MS - 33 * HOUR, FIXED_TIME_MS - 16 * HOUR - 20 * MINUTE, TODAY - 2, false, 100, 58, 2_050.0, AppUsageHint.FAILED),
)

@Composable
private fun HistoryPreviewContent(state: HistoryUiState) {
    HistoryContent(state = state, onEvent = {})
}

private val daysWeek = HistoryUiState(
    mode = HistoryMode.DAYS,
    range = DayRange.WEEK,
    todayEpochDay = TODAY,
    monitoring = true,
    days = daysState(DayRange.WEEK),
    sessions = SessionsState(loading = false),
)

private val sessionsAll = HistoryUiState(
    mode = HistoryMode.SESSIONS,
    todayEpochDay = TODAY,
    monitoring = true,
    days = daysState(DayRange.WEEK),
    sessions = SessionsState(rows = sessionRows(), loading = false, hasMore = true),
)

/** Days, the last 7 days: stacked screen-on / screen-off drain, the daily average and the daily totals. */
@PreviewTest
@ScreenPreviews
@Composable
fun HistoryScreenPreview() {
    ScreenshotTheme { HistoryPreviewContent(daysWeek) }
}

/** 30 days with yesterday selected: its bar highlighted, its figures in place of the average, its row marked. */
@PreviewTest
@TallPhonePreview
@Composable
fun HistoryScreenDaySelectedPreview() {
    ScreenshotTheme {
        HistoryPreviewContent(daysWeek.copy(range = DayRange.MONTH, days = daysState(DayRange.MONTH), selectedDay = TODAY - 1))
    }
}

/** Sessions, all types: the one being recorded, a charge, a discharge without app usage, a plugged row. */
@PreviewTest
@ScreenPreviews
@Composable
fun HistorySessionsPreview() {
    ScreenshotTheme { HistoryPreviewContent(sessionsAll) }
}

/** Days with nothing recorded and monitoring off. */
@PreviewTest
@PhonePreview
@Composable
fun HistoryScreenEmptyPreview() {
    ScreenshotTheme {
        HistoryPreviewContent(
            HistoryUiState(todayEpochDay = TODAY, days = DaysState(days = daysState(DayRange.WEEK).days.map { it.copy(figures = null) }, loading = false)),
        )
    }
}

/** Sessions filtered to charging, none yet. */
@PreviewTest
@PhonePreview
@Composable
fun HistorySessionsEmptyPreview() {
    ScreenshotTheme {
        HistoryPreviewContent(sessionsAll.copy(filter = SessionFilter.CHARGE, sessions = SessionsState(loading = false)))
    }
}

/** The database couldn't be read. */
@PreviewTest
@PhonePreview
@Composable
fun HistoryScreenFailedPreview() {
    ScreenshotTheme {
        HistoryPreviewContent(sessionsAll.copy(sessions = SessionsState(loading = false, failed = true)))
    }
}
