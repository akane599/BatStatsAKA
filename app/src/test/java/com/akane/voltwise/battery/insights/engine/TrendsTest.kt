package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.model.DayInput
import com.akane.voltwise.battery.insights.model.Direction
import com.akane.voltwise.battery.insights.model.Metric
import org.junit.Assert.*
import org.junit.Test

class TrendsTest {
    @Test fun `opposite device trends have different keys for the same subject and metric`() {
        val up = Trends.detect(deviceInputs(100_000, 200_000)).single()
        val down = Trends.detect(deviceInputs(200_000, 100_000)).single()

        assertEquals(Direction.UP, up.direction)
        assertEquals(Direction.DOWN, down.direction)
        assertEquals(up.subject, down.subject)
        assertEquals(Metric.SCREEN_OFF_PCT_PER_H, up.evidence.single().metric)
        assertEquals(up.evidence.single().metric, down.evidence.single().metric)
        assertNotEquals("Opposite directions must have separate finding identities", up.key, down.key)
    }

    @Test fun `device trend key stays stable across runs in the same direction`() {
        val first = Trends.detect(deviceInputs(100_000, 200_000)).single()
        val later = Trends.detect(deviceInputs(100_000, 300_000, dayOffset = 1)).single()

        assertEquals(Direction.UP, first.direction)
        assertEquals(Direction.UP, later.direction)
        assertNotEquals(first.series, later.series)
        assertEquals("Repeated UP changes must keep the same finding identity", first.key, later.key)
    }

    @Test fun `opposite app trends have different keys for the same subject and metric`() {
        val up = Trends.detect(appInputs(5.0, 15.0)).single()
        val down = Trends.detect(appInputs(15.0, 5.0)).single()

        assertEquals(Direction.UP, up.direction)
        assertEquals(Direction.DOWN, down.direction)
        assertEquals(up.subject, down.subject)
        assertEquals(Metric.POWER_MAH_PER_H, up.evidence.single().metric)
        assertEquals(up.evidence.single().metric, down.evidence.single().metric)
        assertNotEquals("Opposite directions must have separate finding identities", up.key, down.key)
    }

    @Test fun `app trend key stays stable across runs in the same direction`() {
        val first = Trends.detect(appInputs(5.0, 15.0)).single()
        val later = Trends.detect(appInputs(5.0, 20.0, dayOffset = 1)).single()

        assertEquals(Direction.UP, first.direction)
        assertEquals(Direction.UP, later.direction)
        assertNotEquals(first.series, later.series)
        assertEquals("Repeated UP changes must keep the same finding identity", first.key, later.key)
    }

    private fun deviceInputs(before: Long, after: Long, dayOffset: Long = 0) =
        inputs(emptyList(), emptyList()).copy(
            nowMs = (100 + dayOffset) * 24 * HOUR,
            todayEpochDay = 100 + dayOffset,
            days = ((76L..79).map { it to before } + (93L..96).map { it to after }).map { (epoch, offUah) ->
                DayInput(epoch + dayOffset, 0, HOUR, 0, HOUR, 0, offUah, 0, null, null, null, null, null)
            },
        )

    private fun appInputs(before: Double, after: Double, dayOffset: Int = 0) =
        ((75..78) + (92..95)).map { session(it + dayOffset) }.let { sessions ->
            inputs(sessions, sessions.mapIndexed { index, session ->
                row(session.id).copy(powerMah = if (index < 4) before else after)
            }).copy(todayEpochDay = 100L + dayOffset)
        }
}
