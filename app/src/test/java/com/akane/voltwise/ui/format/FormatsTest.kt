package com.akane.voltwise.ui.format

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import com.akane.voltwise.R
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatsTest {
    private val us = Locale.US
    private val es = Locale.forLanguageTag("es-ES")
    private val tr = Locale.forLanguageTag("tr-TR")
    private val quiet = SpanStyle(color = Color.Red)
    private val minute = 60_000L
    private val hour = 60 * minute

    @Test fun numbersUseTheLocaleAndTheTypographicMinus() {
        assertEquals("1,240.5", formatNumber(1_240.5, 1, us))
        assertEquals("1.240,5", formatNumber(1_240.5, 1, tr))
        assertEquals("12.480", formatNumber(12_480.0, 0, es))
        assertEquals("−31,5", formatNumber(-31.5, 1, tr))
        assertEquals("0.35", formatRate(0.349, us))
        assertEquals("9.1", formatRate(9.14, us))
    }

    @Test fun onePercentRuleForEveryLocale() {
        // The three `percent_value` templates (en, es, tr): the sign's side for a StatCell, the text for the rest.
        val en = percentUnitOf("%1\$s%%")
        val esUnit = percentUnitOf("%1\$s %%")
        val trUnit = percentUnitOf("%%%1\$s")
        assertEquals("%", en.sign)
        assertFalse(en.first)
        assertFalse(esUnit.first)
        assertTrue("Turkish writes %94", trUnit.first)
        assertEquals("%94", styledTemplate("%%%1\$s", listOf("94"), quiet).text)
        assertEquals("94 %", styledTemplate("%1\$s %%", listOf("94"), quiet).text)
    }

    @Test fun milliampHoursKeepADecimalBelowTen() {
        assertEquals("0.4", formatMah(0.4, us))
        assertEquals("812", formatMah(812.3, us))
        assertEquals("9,9", formatMah(9.94, tr))
        assertEquals("1.240", formatMah(1_240.0, tr))
    }

    @Test fun signedValuesShowTheirDirectionButNeverASignedZero() {
        assertEquals("−412", formatSigned(-412.0, 0, us))
        assertEquals("+1,452", formatSigned(1_452.0, 0, us))
        assertEquals("−1,59", formatSigned(-1.59, 2, tr))
        assertEquals("+6,21", formatSigned(6.21, 2, es))
        assertEquals("0", formatSigned(-0.4, 0, us))
        assertEquals("0", formatSigned(0.3, 0, us))
    }

    @Test fun durationsPickTheirTemplate() {
        with(durationText(5 * hour + 40 * minute + 59_000)) {
            assertEquals(R.string.now_duration_hours_minutes, template)
            assertEquals(listOf(5L, 40L), numbers)
        }
        with(durationText(26 * hour + 10 * minute)) {
            assertEquals(R.string.now_duration_days_hours, template)
            assertEquals(listOf(1L, 2L), numbers)
        }
        with(durationText(45 * minute)) {
            assertEquals(R.string.now_duration_minutes, template)
            assertEquals(listOf(45L), numbers)
        }
        assertEquals(R.string.now_duration_under_minute, durationText(59_000).template)
        assertEquals(R.string.now_duration_under_minute, durationText(-5).template)
    }

    @Test fun compactDurationsAreHoursColonMinutesOrMinutes() {
        with(compactDuration(2 * hour + 10 * minute, us)) {
            assertEquals("2:10", value)
            assertEquals(R.string.now_unit_hours, unit)
        }
        assertEquals("2:05", compactDuration(2 * hour + 5 * minute, tr).value)
        with(compactDuration(45 * minute, es)) {
            assertEquals("45", value)
            assertEquals(R.string.now_unit_minutes, unit)
        }
    }

    @Test fun templatesKeepEachLocalesOrderAndQuietTheLiteralParts() {
        val duration = styledTemplate("%1\$s h %2\$s min", listOf("5", "40"), quiet)
        assertEquals("5 h 40 min", duration.text)
        assertEquals(listOf(" h ", " min"), duration.spanStyles.map { duration.text.substring(it.start, it.end) })
        assertEquals(setOf(quiet), duration.spanStyles.map { it.item }.toSet())

        // Turkish puts the percent sign first, Spanish after a space; "%%" is a literal percent.
        val turkish = styledTemplate("%%%1\$s", listOf("78"), quiet)
        assertEquals("%78", turkish.text)
        assertEquals(listOf("%"), turkish.spanStyles.map { turkish.text.substring(it.start, it.end) })
        assertEquals("78 %", styledTemplate("%1\$s %%", listOf("78"), quiet).text)
        assertEquals("<1 min", styledTemplate("<1 min", emptyList(), quiet).text)
    }

    @Test fun sameLocalDayComparesCalendarDaysInTheGivenZone() {
        val utc = TimeZone.getTimeZone("UTC")
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        val lateEvening = 1_760_000_000_000L - 1_760_000_000_000L % (24 * hour) + 23 * hour // 23:00 UTC
        assertTrue(sameLocalDay(lateEvening, lateEvening - 22 * hour, utc))
        assertFalse(sameLocalDay(lateEvening, lateEvening + 2 * hour, utc))
        // 23:00 UTC is 08:00 the next day in Tokyo, so 21:00 UTC (06:00) is the same Tokyo day and 13:00 UTC is not.
        assertTrue(sameLocalDay(lateEvening, lateEvening - 2 * hour, tokyo))
        assertFalse(sameLocalDay(lateEvening, lateEvening - 10 * hour, tokyo))
    }
}
