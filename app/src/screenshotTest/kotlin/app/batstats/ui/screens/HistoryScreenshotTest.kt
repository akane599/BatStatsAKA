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
import app.batstats.viewmodel.DrainState
import app.batstats.viewmodel.DrainUnit
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

// The battery's full charge when the capacity is known (DrainUnit.PERCENT).
private const val FULL_MAH = 4_500.0

/**
 * Figures as HistoryMapping makes them, with the counter covering all the screen time: drain rates in mA (and %/h
 * with a capacity), totals in % of [FULL_MAH] or mAh. A null charge is a screen state the counter didn't measure.
 */
private fun dayFigures(onMs: Long, offMs: Long, onMah: Double?, offMah: Double?, chargedMah: Double, deepSleep: Double?, unit: DrainUnit): DayFigures {
    val percent = unit == DrainUnit.PERCENT
    fun total(mah: Double) = if (percent) mah * 100 / FULL_MAH else mah
    fun drain(ms: Long, mah: Double?): DrainState {
        val milliamps = mah?.let { it * HOUR / ms }
        return DrainState(ms, milliamps, milliamps?.takeIf { percent }?.let { it * 100 / FULL_MAH })
    }
    val on = onMah?.let(::total)
    val off = offMah?.let(::total)
    return DayFigures(
        screenOn = drain(onMs, onMah),
        screenOff = drain(offMs, offMah),
        screenOnUsed = on,
        screenOffUsed = off,
        used = if (on == null && off == null) null else (on ?: 0.0) + (off ?: 0.0),
        charged = total(chargedMah),
        deepSleepPercent = deepSleep,
    )
}

/** A plausible day: heavier screen-on drain on some days, a charge most days, one gap (not monitored), one day unmeasured. */
private fun figures(dayIndex: Int, unit: DrainUnit): DayFigures? {
    if (dayIndex % 11 == 4) return null
    val wave = 0.5 + 0.5 * sin(dayIndex * 1.7)
    val onMs = ((1.2 + 3.1 * wave) * HOUR).toLong()
    val offMs = ((9.0 + 4.0 * (1 - wave)) * HOUR).toLong()
    val measured = dayIndex != UNMEASURED_DAY_INDEX
    return dayFigures(
        onMs = onMs,
        offMs = offMs,
        onMah = (240.0 + 610 * wave).takeIf { measured },
        offMah = (70.0 + 95 * (1 - wave) + if (dayIndex % 6 == 2) 260 else 0).takeIf { measured },
        chargedMah = if (dayIndex % 3 == 1) 0.0 else 1_180.0 + 400 * wave,
        deepSleep = if (dayIndex % 6 == 2) 41.0 else 86.0 + 8 * wave,
        unit = unit,
    )
}

/** Days back from today of the day with screen time but no measured charge. */
private const val UNMEASURED_DAY_INDEX = 2

private fun daysState(range: DayRange, unit: DrainUnit = DrainUnit.PERCENT): DaysState {
    val days = (TODAY - range.days + 1..TODAY).map { day -> DayEntry(day, figures((TODAY - day).toInt(), unit)) }
    // Today is only 9 hours old: a partial day.
    val partial = days.map { entry ->
        if (entry.epochDay != TODAY) entry
        else entry.copy(figures = dayFigures(2 * HOUR + 10 * MINUTE, 5 * HOUR, 412.0, 96.0, 0.0, 88.0, unit))
    }
    return DaysState(
        days = partial,
        average = dayFigures(3 * HOUR + 5 * MINUTE, 11 * HOUR + 20 * MINUTE, 521.0, 168.0, 905.0, 84.0, unit),
        unit = unit,
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

/** The capacity isn't known yet: bars, totals and charged in mAh, the average day's drain in mA. */
@PreviewTest
@TallPhonePreview
@Composable
fun HistoryScreenMahPreview() {
    ScreenshotTheme { HistoryPreviewContent(daysWeek.copy(days = daysState(DayRange.WEEK, DrainUnit.MAH))) }
}

/**
 * A day with screen time the counter didn't measure, selected: dashes and a note, not zeros. Its stored charge was 0,
 * so Charged is unknown too (a dash, as on Now › Today) and its row names no charge. Tall, so the figures show.
 */
@PreviewTest
@TallPhonePreview
@Composable
fun HistoryScreenUnmeasuredDayPreview() {
    val unmeasured = TODAY - UNMEASURED_DAY_INDEX
    val days = daysWeek.days.days.map { day -> if (day.epochDay == unmeasured) day.copy(figures = day.figures?.copy(charged = null)) else day }
    ScreenshotTheme { HistoryPreviewContent(daysWeek.copy(days = daysWeek.days.copy(days = days), selectedDay = unmeasured)) }
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
