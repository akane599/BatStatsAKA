package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.model.DayInput
import com.akane.voltwise.battery.insights.model.Metric
import org.junit.Assert.*
import org.junit.Test

class TrendThresholdTest {
    @Test fun `daily relative floor rejects large absolute change on a high baseline`() {
        val days = (76L..79).map { day(it, 1_000_000) } + (93L..96).map { day(it, 1_100_000) }
        val input = inputs(emptyList(), emptyList()).copy(days = days)
        assertTrue(Trends.detect(input).isEmpty())
        val changed = input.copy(days = days.map {
            if (it.epochDay >= 93) it.copy(screenOffDischargeUah = 1_400_000) else it
        })
        assertEquals(setOf(Metric.SCREEN_OFF_PCT_PER_H, Metric.DAILY_USE_PCT),
            Trends.detect(changed).map { it.evidence.single().metric }.toSet())
    }

    @Test fun `app comparison requires measured values and both relative and absolute floors`() {
        val sessions = (75..78).map { session(it) } + (92..95).map { session(it) }
        fun report(before: Double, after: Double) = Trends.detect(inputs(sessions,
            sessions.mapIndexed { i, s -> row(s.id).copy(powerMah = if (i < 4) before else after) }))
        assertTrue(report(100.0, 110.0).isEmpty())
        assertTrue(report(1.0, 2.0).isEmpty())
        assertEquals(1, report(5.0, 10.0).size)
        assertTrue(report(Double.NaN, 10.0).isEmpty())
    }

    private fun day(epoch: Long, energy: Long) = DayInput(
        epoch, 0, HOUR, 0, HOUR, 0, energy, 0, null, null, null, null, null,
    )
}
