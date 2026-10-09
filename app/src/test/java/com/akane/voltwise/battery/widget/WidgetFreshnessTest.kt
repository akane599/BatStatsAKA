package com.akane.voltwise.battery.widget

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetFreshnessTest {
    @Test fun system24HourClockSelects24HourSkeletonForUsLocale() {
        val pattern = widgetFreshnessPattern(is24h = true, locale = Locale.US) { locale, skeleton ->
            assertEquals(Locale.US, locale)
            skeleton
        }

        assertEquals("yMdHm", pattern)
    }

    @Test fun system12HourClockSelects12HourSkeletonForGermanLocale() {
        val pattern = widgetFreshnessPattern(is24h = false, locale = Locale.GERMANY) { locale, skeleton ->
            assertEquals(Locale.GERMANY, locale)
            skeleton
        }

        assertEquals("yMdhm", pattern)
    }

    @Test fun scriptedReadingUsesFullYearFromSystemClockPattern() {
        // Same timestamp as WidgetDeliveryDeviceTest; fake Android's US 12-hour best-pattern lookup.
        val pattern = widgetFreshnessPattern(is24h = false, locale = Locale.US) { _, _ ->
            "M/d/y, h:mm a"
        }
        val formatter = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

        assertEquals(
            "Scripted widget freshness must use the system-clock pattern, not locale SHORT",
            "5/19/2026, 10:00 AM",
            formatter.format(Date(1_779_184_800_000L)),
        )
    }

    @Test fun usFreshnessUses1545WhenSystem24HourClockIsEnabled() {
        // A JVM pattern resolver stands in for Android's locale-specific best-pattern lookup.
        val pattern = widgetFreshnessPattern(is24h = true, locale = Locale.US) { _, skeleton ->
            assertEquals("yMdHm", skeleton)
            "M/d/yy, HH:mm"
        }
        val formatter = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val timestamp = Date(15 * 60 * 60 * 1000L + 45 * 60 * 1000L)

        assertEquals("1/1/70, 15:45", formatter.format(timestamp))
    }
}
