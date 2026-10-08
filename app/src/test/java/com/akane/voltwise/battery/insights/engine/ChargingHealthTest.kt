package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.engine.detectors.device.ChargingHealth
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
