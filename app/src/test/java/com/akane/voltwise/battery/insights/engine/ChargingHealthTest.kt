package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.engine.detectors.device.ChargingHealth
import com.akane.voltwise.battery.insights.engine.stats.TheilSen
import com.akane.voltwise.battery.insights.engine.stats.TimedValue
import com.akane.voltwise.battery.insights.model.CapacityPointInput
import com.akane.voltwise.battery.insights.model.Direction
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.SessionKind
import org.junit.Assert.*
import org.junit.Test

class ChargingHealthTest {
    @Test fun `full charging requires three observed two hour full plugged sessions in fourteen days`() {
        val sessions = (0..2).map { session(it, 2 * HOUR).copy(kind = SessionKind.PLUGGED, startLevel = 100, endLevel = 100) }
        val input = inputs(sessions, emptyList())
        val finding = ChargingHealth.detect(input).single()
        assertEquals(FindingType.CHARGING_AT_FULL, finding.type)
        assertEquals(3, finding.evidence.single().sessions)
        assertEquals(2.0 * HOUR, finding.evidence.single().observed, 0.0)
        assertTrue(ChargingHealth.detect(input.copy(sessions = sessions.drop(1))).isEmpty())
        assertTrue(ChargingHealth.detect(input.copy(sessions = sessions.map { it.copy(startLevel = null) })).isEmpty())
        assertTrue(ChargingHealth.detect(input.copy(sessions = sessions.map { it.copy(startLevel = 99) })).isEmpty())
        assertTrue(ChargingHealth.detect(input.copy(sessions = sessions.map { it.copy(observedMs = HOUR) })).isEmpty())
        assertTrue(ChargingHealth.detect(input.copy(nowMs = input.nowMs + 14 * 24 * HOUR)).isEmpty())
    }

    @Test fun `hot charging requires repeated measured peaks at forty degrees`() {
        val sessions = (0..2).map { session(it).copy(kind = SessionKind.CHARGE, peakTemperatureDeciC = 400) }
        val input = inputs(sessions, emptyList())
        assertEquals(FindingType.HOT_CHARGING, ChargingHealth.detect(input).single().type)
        assertTrue(ChargingHealth.detect(input.copy(sessions = sessions.drop(1))).isEmpty())
        assertTrue(ChargingHealth.detect(input.copy(sessions = sessions.map { it.copy(peakTemperatureDeciC = 399) })).isEmpty())
        assertTrue(ChargingHealth.detect(input.copy(sessions = sessions.map { it.copy(peakTemperatureDeciC = null) })).isEmpty())
        assertTrue(ChargingHealth.detect(input.copy(sessions = sessions.map { it.copy(kind = SessionKind.DISCHARGE) })).isEmpty())
    }

    @Test fun `flat capacity estimate noise does not indicate health decline`() {
        val points = listOf(4000.0, 4080.0, 3920.0, 4040.0, 3960.0).mapIndexed { index, mah ->
            CapacityPointInput(index * 180 * HOUR, mah, 2)
        }
        val input = inputs(emptyList(), emptyList()).copy(nowMs = 30 * 24 * HOUR, capacity = points)
        assertTrue("Flat noisy estimates must not report HEALTH_DECLINE", ChargingHealth.detect(input).isEmpty())
    }

    @Test fun `clustered recent capacity estimates do not indicate health decline`() {
        val day = 24 * HOUR
        val points = listOf(CapacityPointInput(0, 4000.0, 2)) +
            listOf(3900.0, 4000.0, 3950.0, 3950.0).mapIndexed { index, mah ->
                CapacityPointInput(30 * day + index * HOUR, mah, 2)
            }
        val input = inputs(emptyList(), emptyList()).copy(nowMs = 30 * day + 3 * HOUR, capacity = points)
        assertTrue("A noisy recent cluster must not report HEALTH_DECLINE", ChargingHealth.detect(input).isEmpty())
    }

    @Test fun `steady material capacity decline still produces negative annual health evidence`() {
        val day = 24 * HOUR
        val points = listOf(4400.0, 4358.0, 4311.0, 4269.0, 4225.0, 4180.0).mapIndexed { index, mah ->
            CapacityPointInput(index * 12 * day, mah, 2)
        }
        val input = inputs(emptyList(), emptyList()).copy(nowMs = 60 * day, capacity = points)
        val finding = ChargingHealth.detect(input).single()
        assertEquals(FindingType.HEALTH_DECLINE, finding.type)
        assertEquals(Direction.DOWN, finding.direction)
        assertEquals(Metric.CAPACITY_CHANGE_PCT_PER_YEAR, finding.evidence.single().metric)
        assertTrue("A steady decline must retain negative annual evidence", finding.evidence.single().observed < 0.0)
    }

    @Test fun `health requires at least seventy five percent negative pairwise slopes`() {
        val day = 24 * HOUR
        // Seven of 28 pairs rise; the remaining 21 decline, exactly 75%.
        val points = listOf(3, 6, 5, 4, 7, 2, 1, 0).mapIndexed { index, value ->
            CapacityPointInput(index * 7 * day, 4000.0 + value * 10, 2)
        }
        val input = inputs(emptyList(), emptyList()).copy(nowMs = 49 * day, capacity = points)
        assertEquals(FindingType.HEALTH_DECLINE, ChargingHealth.detect(input).single().type)
        val belowThreshold = points.mapIndexed { index, point ->
            when (index) {
                6 -> point.copy(mah = points[7].mah)
                7 -> point.copy(mah = points[6].mah)
                else -> point
            }
        }
        assertTrue("Twenty of 28 negative pairs are not a clear decline", ChargingHealth.detect(input.copy(capacity = belowThreshold)).isEmpty())
    }

    @Test fun `health requires a material annual decline even when every pair declines`() {
        val day = 24 * HOUR
        val points = (0..4).map { CapacityPointInput(it * 15 * day, 4000.0 + (2 - it) * 4, 2) }
        val input = inputs(emptyList(), emptyList()).copy(nowMs = 60 * day, capacity = points)
        // About -2.44% per year: direction is clear, but magnitude is not material.
        assertTrue("A clear decline under three percent per year must stay silent", ChargingHealth.detect(input).isEmpty())
        val material = points.mapIndexed { index, point -> point.copy(mah = 4000.0 + (2 - index) * 5) }
        assertEquals(FindingType.HEALTH_DECLINE, ChargingHealth.detect(input.copy(capacity = material)).single().type)
    }

    @Test fun `negative slope share excludes equal timestamps but includes flat pairs`() {
        val points = listOf(TimedValue(0, 10.0), TimedValue(0, 8.0), TimedValue(1, 6.0), TimedValue(2, 8.0))
        val trend = requireNotNull(TheilSen.trend(points))
        assertEquals(-1.0, trend.slope, 0.0)
        assertEquals(3.0 / 5, trend.negativeSlopeShare, 0.0)
        assertEquals(trend, TheilSen.trend(points.reversed()))
        assertNull(TheilSen.trend(listOf(TimedValue(0, 10.0), TimedValue(0, 8.0))))
    }

    @Test fun `health uses robust slope converted to annual percentage with point span confidence and age gates`() {
        val day = 24 * HOUR
        val points = (0..4).map { CapacityPointInput(it * 10 * day, 4000.0 - it * 10, 2) }
        val input = inputs(emptyList(), emptyList()).copy(nowMs = 40 * day, capacity = points)
        val finding = ChargingHealth.detect(input).single()
        assertEquals(FindingType.HEALTH_DECLINE, finding.type)
        assertEquals(Direction.DOWN, finding.direction)
        assertEquals(Metric.CAPACITY_CHANGE_PCT_PER_YEAR, finding.evidence.single().metric)
        assertEquals(-365.25 / 3980 * 100, finding.evidence.single().observed, 0.0001)
        assertTrue(ChargingHealth.detect(input.copy(capacity = points.drop(1))).isEmpty())
        assertTrue(ChargingHealth.detect(input.copy(capacity = points.map { it.copy(atMs = it.atMs / 2) })).isEmpty())
        assertTrue(ChargingHealth.detect(input.copy(capacity = points.map { it.copy(confidence = 1) })).isEmpty())
        assertTrue(ChargingHealth.detect(input.copy(capacity = points.map { it.copy(mah = 4000.0) })).isEmpty())
        assertTrue(ChargingHealth.detect(input.copy(nowMs = 131 * day)).isEmpty())
        val outlier = input.copy(capacity = points.mapIndexed { i, p -> if (i == 2) p.copy(mah = 9000.0) else p })
        assertEquals(Direction.DOWN, ChargingHealth.detect(outlier).single().direction)
        assertEquals(finding, ChargingHealth.detect(input.copy(capacity = points.reversed())).single())
    }
}
