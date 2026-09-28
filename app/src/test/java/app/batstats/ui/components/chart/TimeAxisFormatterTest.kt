package app.batstats.ui.components.chart

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class TimeAxisFormatterTest {
    // JVM tests have no ICU skeleton data: this en-US table stands in for DateFormat.getBestDateTimePattern.
    private val patterns = mapOf(
        "Hms" to "HH:mm:ss",
        "hms" to "h:mm:ss a",
        "Hm" to "HH:mm",
        "hm" to "h:mm a",
        "ha" to "h a",
        "MMMd" to "MMM d",
        "MMM" to "MMM",
        "MMMdHm" to "MMM d, HH:mm",
        "MMMdhm" to "MMM d, h:mm a",
    )

    private fun formatter(zone: String, use24Hour: Boolean) =
        TimeAxisFormatter.create(Locale.US, TimeZone.getTimeZone(zone), use24Hour) { _, skeleton -> patterns.getValue(skeleton) }

    private val nineTwentyUtc = 1_760_001_600_000L // 2025-10-09 09:20 UTC
    private val midnightUtc = 1_759_968_000_000L // 2025-10-09 00:00 UTC

    @Test
    fun theTwelveOrTwentyFourHourSettingChangesEveryTimeLabel() {
        val h24 = formatter("UTC", use24Hour = true)
        val h12 = formatter("UTC", use24Hour = false)
        assertEquals("09:20", h24.format(nineTwentyUtc, TimeGranularity.MINUTES))
        assertEquals("9:20 AM", h12.format(nineTwentyUtc, TimeGranularity.MINUTES))
        assertEquals("09:20:00", h24.format(nineTwentyUtc, TimeGranularity.SECONDS))
        assertEquals("Oct 9, 9:20 AM", h12.format(nineTwentyUtc, TimeGranularity.DATE_TIME))
        // 12 h minute ticks drop the day period to fit more labels; hour ticks keep it.
        assertEquals("9:20", h12.axisLabel(nineTwentyUtc, TimeGranularity.MINUTES))
        assertEquals("9 AM", h12.axisLabel(nineTwentyUtc, TimeGranularity.HOURS))
    }

    @Test
    fun theTimeZoneChangesTimesAndWhichTickIsMidnight() {
        val utc = formatter("UTC", use24Hour = true)
        val kolkata = formatter("Asia/Kolkata", use24Hour = true)
        assertEquals("Asia/Kolkata", kolkata.zone.id)
        assertEquals("14:50", kolkata.format(nineTwentyUtc, TimeGranularity.MINUTES))
        // UTC midnight is a date tick in UTC but 05:30 in Kolkata.
        assertEquals("Oct 9", utc.axisLabel(midnightUtc, TimeGranularity.HOURS))
        assertEquals("05:30", kolkata.axisLabel(midnightUtc, TimeGranularity.HOURS))
    }
}
