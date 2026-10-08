package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.engine.detectors.device.ChargingHealth
import com.akane.voltwise.battery.insights.engine.detectors.device.DeviceDetectors
import com.akane.voltwise.battery.insights.engine.recommend.Recommender
import com.akane.voltwise.battery.insights.model.ActionStatus
import com.akane.voltwise.battery.insights.model.ActionType
import com.akane.voltwise.battery.insights.model.AppliedActionInput
import com.akane.voltwise.battery.insights.model.Confidence
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.SessionKind
import com.akane.voltwise.battery.insights.model.Severity
import com.akane.voltwise.battery.insights.model.Subject
import org.junit.Assert.*
import org.junit.Test

class InsightEngineTest {
    @Test fun `recommendations cover every finding type and preserve manual privileged paths`() {
        val input = inputs(emptyList(), emptyList()).copy(privileged = false)
        val base = listOf(ActionType.RESTRICT_BACKGROUND, ActionType.STANDBY_BUCKET_RESTRICTED, ActionType.OPEN_APP_SETTINGS)
        for (type in FindingType.entries) {
            val expected = when (type) {
                FindingType.APP_DRAIN_ANOMALY, FindingType.NEW_HEAVY_APP, FindingType.STUCK_WAKELOCK,
                FindingType.WAKEUP_STORM, FindingType.JOB_STORM, FindingType.BACKGROUND_LOCATION, FindingType.BACKGROUND_RADIO -> base
                FindingType.BACKGROUND_RUNAWAY, FindingType.LINGERING_FOREGROUND_SERVICE -> base.dropLast(1) + ActionType.FORCE_STOP + base.last()
                FindingType.DOZE_WHITELISTED_DRAINER -> listOf(ActionType.REMOVE_DOZE_WHITELIST, ActionType.OPEN_BATTERY_OPTIMIZATION_SETTINGS)
                FindingType.DOZE_BLOCKED, FindingType.SCREEN_OFF_DRAIN_HIGH -> listOf(ActionType.OPEN_BATTERY_OPTIMIZATION_SETTINGS)
                FindingType.CHARGING_AT_FULL, FindingType.HOT_CHARGING -> listOf(ActionType.ENABLE_HIGH_BATTERY_ALERT)
                else -> emptyList()
            }
            val candidate = finding(type, emptyList(), subject = Subject.App(UID, APP))
            val result = Recommender.recommend(candidate, input)
            assertEquals(type.name, expected, result.recommendations.map { it.action })
            result.recommendations.forEach { rec ->
                assertEquals(rec.action != ActionType.FORCE_STOP, rec.reversible)
                assertEquals(rec.action in setOf(ActionType.RESTRICT_BACKGROUND, ActionType.STANDBY_BUCKET_RESTRICTED,
                    ActionType.REMOVE_DOZE_WHITELIST, ActionType.FORCE_STOP), rec.requiresPrivilege)
            }
            assertEquals(result, Recommender.recommend(candidate, input.copy(privileged = true)))
        }
    }

    @Test fun `only applied package plus type suppresses recommendation`() {
        val action = AppliedActionInput(1, "old", ActionType.RESTRICT_BACKGROUND, APP, UID, 0, ActionStatus.APPLIED)
        val candidate = finding(FindingType.APP_DRAIN_ANOMALY, emptyList(), Subject.App(UID, APP))
        val input = inputs(emptyList(), emptyList()).copy(actions = listOf(action))
        assertEquals(2, Recommender.recommend(candidate, input).recommendations.size)
        assertEquals(3, Recommender.recommend(candidate, input.copy(actions = listOf(action.copy(status = ActionStatus.REVERTED)))).recommendations.size)
        assertEquals(3, Recommender.recommend(candidate, input.copy(actions = listOf(action.copy(packageName = "other.app")))).recommendations.size)
    }

    @Test fun `rank sorts severity then confidence then score then stable key`() {
        val low = finding(FindingType.NEW_HEAVY_APP, emptyList(), severity = Severity.LOW, score = 100.0)
        val high = low.copy(key = "high", severity = Severity.HIGH, score = 1.0)
        val confidence = high.copy(key = "confidence", confidence = Confidence.HIGH, score = 0.0)
        val score = confidence.copy(key = "score", score = 2.0)
        val tie = score.copy(key = "aaa")
        assertEquals(listOf(tie, score, confidence, high, low), listOf(low, high, score, tie, confidence).sortedWith(findingOrder))
    }

    @Test fun `analyze globally caps ranks and selects noninformational headline deterministically`() {
        val base = detectorInputs(FindingType.APP_DRAIN_ANOMALY)
        val rows = base.appSessions.flatMap { row -> (1..4).map { row.copy(packageName = "app.n$it", uid = UID + it) } }
        val plugged = (0..2).map { session(it + 5, 2 * HOUR).copy(kind = SessionKind.PLUGGED, startLevel = 100, endLevel = 100) }
        val input = base.copy(sessions = base.sessions + plugged, appSessions = rows, nowMs = plugged.last().endMs + HOUR)
        val candidates = appFindings(input) + DeviceDetectors.detect(input) + Trends.detect(input) + ChargingHealth.detect(input) + ActionEffects.detect(input)
        assertTrue(candidates.size > 12)
        val report = InsightEngine.analyze(input)
        assertEquals(12, report.findings.size)
        assertEquals(candidates.sortedWith(findingOrder).take(12).map { it.key }, report.findings.map { it.key })
        assertEquals(report.findings.first { it.severity != Severity.INFO }, report.headline)
        assertEquals(input.nowMs, report.generatedAtMs)
        assertEquals(report, InsightEngine.analyze(input))
        assertEquals(report, InsightEngine.analyze(input.copy(sessions = input.sessions.reversed(), appSessions = rows.reversed())))
        assertNull(InsightEngine.analyze(inputs(emptyList(), emptyList())).headline)
        val action = AppliedActionInput(9, "APP_DRAIN_ANOMALY:$APP", ActionType.RESTRICT_BACKGROUND, APP, UID,
            base.sessions[2].startMs, ActionStatus.APPLIED)
        val effectOnly = base.copy(appSessions = base.appSessions.mapIndexed { i, r ->
            row(r.sessionId).copy(powerMah = if (i < 2) 20.0 else 5.0)
        }, actions = listOf(action))
        val info = InsightEngine.analyze(effectOnly)
        assertTrue(info.findings.isNotEmpty())
        assertTrue(info.findings.all { it.severity == Severity.INFO })
        assertNull(info.headline)
    }
}
