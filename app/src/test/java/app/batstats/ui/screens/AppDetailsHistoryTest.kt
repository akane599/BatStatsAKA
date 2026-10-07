package app.batstats.ui.screens

import app.batstats.ui.components.chart.TimeAxisFormatter
import app.batstats.viewmodel.AppSessionUsage
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class AppDetailsHistoryTest {
    // JVM tests have no ICU skeleton data: an en-US table stands in for DateFormat.getBestDateTimePattern.
    private val patterns = mapOf(
        "Hms" to "HH:mm:ss",
        "Hm" to "HH:mm",
        "MMMd" to "MMM d",
        "MMM" to "MMM",
        "MMMdHm" to "MMM d, HH:mm",
    )
    private val formatter =
        TimeAxisFormatter.create(Locale.US, TimeZone.getTimeZone("UTC"), use24Hour = true) { _, skeleton -> patterns.getValue(skeleton) }

    private val nineTwentyUtc = 1_760_001_600_000L // 2025-10-09 09:20 UTC

    @Test fun sameDaySessionsGetDistinctSpokenLabelsButKeepTheDayUnderTheBar() {
        val sessions = listOf(
            AppSessionUsage("a", nineTwentyUtc, 12.0),
            AppSessionUsage("b", nineTwentyUtc + 5 * 3_600_000L, null),
        )
        val entries = historyBarEntries(sessions, formatter)
        assertEquals(listOf("Oct 9, 09:20", "Oct 9, 14:20"), entries.map { it.label })
        assertEquals(listOf("Oct 9", "Oct 9"), entries.map { it.shortLabel })
        assertEquals(listOf(listOf(12.0), listOf(0.0)), entries.map { it.values })
    }
}
