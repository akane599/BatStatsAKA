package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.engine.detectors.device.DeviceDetectors
import com.akane.voltwise.battery.insights.model.AttributionKind
import com.akane.voltwise.battery.insights.model.DeviceWakerInput
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.MetricUnit
import com.akane.voltwise.battery.insights.model.WakerKind
import org.junit.Assert.*
import org.junit.Test

class DeviceDetectorsTest {
    @Test fun `doze blocked needs two hours both low shares and four baseline sessions`() {
        val input = dozeInput()
        val result = DeviceDetectors.detect(input).single()
        assertEquals(FindingType.DOZE_BLOCKED, result.type)
        assertEquals(0.05, result.evidence.first().observed, 0.0001)
        assertEquals(0.8, result.evidence.first().baseline!!, 0.0001)
        assertTrue(DeviceDetectors.detect(input.copy(sessions = input.sessions.drop(1))).isEmpty())
        for (replacement in listOf(
            input.sessions.last().copy(screenOffDozeMs = null),
            input.sessions.last().copy(screenOffSuspendMs = null),
            input.sessions.last().copy(screenOffMs = HOUR),
            input.sessions.last().copy(screenOffDozeMs = HOUR * 8 / 10),
            input.sessions.last().copy(screenOffSuspendMs = HOUR * 18 / 10),
        )) assertTrue(DeviceDetectors.detect(input.copy(sessions = input.sessions.dropLast(1) + replacement)).isEmpty())
        assertTrue(DeviceDetectors.detect(input.copy(sessions = input.sessions.map {
            it.copy(screenOffDozeMs = HOUR / 10, screenOffSuspendMs = HOUR / 10)
        })).isEmpty())
    }

    @Test fun `screen off drain uses covered time rather than wall time and requires capacity`() {
        val sessions = (0..4).map { session(it).copy(screenOffUah = if (it < 4) 40_000 else 160_000) }
        val input = inputs(sessions, emptyList())
        val finding = DeviceDetectors.detect(input).single()
        assertEquals(FindingType.SCREEN_OFF_DRAIN_HIGH, finding.type)
        assertEquals(4.0, finding.evidence.single().observed, 0.0)
        assertTrue(DeviceDetectors.detect(input.copy(fullUah = null)).isEmpty())
        assertTrue(DeviceDetectors.detect(input.copy(sessions = sessions.drop(1))).isEmpty())
        assertTrue(DeviceDetectors.detect(input.copy(sessions = sessions.map { it.copy(screenOffCoveredMs = null) })).isEmpty())
        assertTrue(DeviceDetectors.detect(input.copy(sessions = sessions.map { it.copy(screenOffUah = 40_000) })).isEmpty())
        val partial = input.copy(sessions = sessions.dropLast(1) + sessions.last().copy(screenOffCoveredMs = HOUR / 2))
        assertEquals(8.0, DeviceDetectors.detect(partial).single().evidence.single().observed, 0.0)
    }

    @Test fun `whitelist flags only top five eligible measured background drainers`() {
        val sessions = listOf(session(0))
        val rows = (1..6).map { n -> row("s0").copy(packageName = "app.n$n", uid = UID + n, bgMs = n * 10_000L) }
        val input = inputs(sessions, rows).copy(dozeUserWhitelist = rows.map { it.packageName }.toSet())
        val findings = DeviceDetectors.detect(input)
        assertEquals(5, findings.size)
        assertTrue(findings.none { it.key.endsWith("app.n1") })
        assertTrue(DeviceDetectors.detect(input.copy(dozeUserWhitelist = null)).isEmpty())
        assertTrue(DeviceDetectors.detect(input.copy(dozeUserWhitelist = emptySet())).isEmpty())
        assertTrue(DeviceDetectors.detect(input.copy(sessions = sessions.map { it.copy(imported = true) })).isEmpty())
        assertTrue(DeviceDetectors.detect(input.copy(appSessions = rows.map { it.copy(bgMs = null, partialWakelockBgMs = null) })).isEmpty())
        assertTrue(DeviceDetectors.detect(input.copy(appSessions = rows.map { it.copy(isOthers = true) })).isEmpty())
    }

    @Test fun `attributions match evaluated session omit unsupported sources cap and break ties by name`() {
        val base = dozeInput()
        val id = base.sessions.last().id
        val wakers = (1..6).map { DeviceWakerInput(id, WakerKind.KERNEL_WAKELOCK, "kernel.$it", 5, 1000) } +
            DeviceWakerInput("other", WakerKind.KERNEL_WAKELOCK, "unrelated", 100, 99999)
        val rows = (1..6).map { row(id).copy(packageName = "app.n$it", uid = UID + it, wakeupAlarms = 20) } +
            row(id).copy(packageName = "app.unsupported", wakeupAlarms = null, partialWakelockBgMs = null)
        val input = base.copy(deviceWakers = wakers, appSessions = rows)
        val finding = DeviceDetectors.detect(input).single()
        assertEquals(10, finding.attributions.size)
        assertEquals((1..5).map { "kernel.$it" }, finding.attributions.take(5).map { it.name })
        assertEquals((1..5).map { "app.n$it" }, finding.attributions.drop(5).map { it.packageName })
        assertTrue(finding.attributions.take(5).all { it.kind == AttributionKind.KERNEL_WAKELOCK && it.unit == MetricUnit.MS })
        assertTrue(finding.attributions.drop(5).all { it.value == 10.0 && it.unit == MetricUnit.COUNT_PER_H })
        assertEquals(finding, DeviceDetectors.detect(input.copy(deviceWakers = wakers.reversed(), appSessions = rows.reversed())).single())
        val fallback = input.copy(deviceWakers = listOf(DeviceWakerInput(id, WakerKind.WAKEUP_REASON, "alarm", 7, 0)),
            appSessions = listOf(row(id).copy(wakeupAlarms = null, partialWakelockBgMs = 60_000)))
        val hints = DeviceDetectors.detect(fallback).single().attributions
        assertEquals(MetricUnit.COUNT, hints[0].unit)
        assertEquals(7.0, hints[0].value, 0.0)
        assertEquals(MetricUnit.MS_PER_H, hints[1].unit)
        assertEquals(30_000.0, hints[1].value, 0.0)
        assertEquals(1, DeviceDetectors.detect(fallback.copy(sessions = base.sessions.map { it.copy(appWindow = null) }))
            .single().attributions.size)
    }

    @Test fun `device attributions reserve slots for wakeup reasons`() {
        val base = dozeInput()
        val id = base.sessions.last().id
        val wakers = (1..6).map { DeviceWakerInput(id, WakerKind.KERNEL_WAKELOCK, "kernel.$it", 5, 60_000L * it) } +
            listOf(
                DeviceWakerInput(id, WakerKind.WAKEUP_REASON, "reason.1", 300, 0),
                DeviceWakerInput(id, WakerKind.WAKEUP_REASON, "reason.2", 200, 0),
            )
        val finding = DeviceDetectors.detect(base.copy(deviceWakers = wakers)).single()
        val deviceAttributions = finding.attributions.takeWhile { it.kind != AttributionKind.APP }
        assertEquals(setOf("reason.1", "reason.2"), deviceAttributions.filter {
            it.kind == AttributionKind.WAKEUP_REASON
        }.map { it.name }.toSet())

        val kernelOnly = DeviceDetectors.detect(base.copy(deviceWakers = wakers.filter {
            it.kind == WakerKind.KERNEL_WAKELOCK
        })).single().attributions.takeWhile { it.kind != AttributionKind.APP }
        assertEquals(5, kernelOnly.size)
        assertTrue(kernelOnly.all { it.kind == AttributionKind.KERNEL_WAKELOCK })
    }

    private fun dozeInput() = inputs((0..4).map {
        session(it, 2 * HOUR).copy(screenOffDozeMs = if (it < 4) HOUR * 16 / 10 else HOUR / 10,
            screenOffSuspendMs = if (it < 4) HOUR * 18 / 10 else HOUR / 10)
    }, emptyList())
}
