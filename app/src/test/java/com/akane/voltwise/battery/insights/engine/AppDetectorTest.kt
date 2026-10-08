package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.model.Confidence
import com.akane.voltwise.battery.insights.model.Finding
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.InsightInputs
import com.akane.voltwise.battery.insights.model.Subject
import com.akane.voltwise.battery.insights.model.WindowBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class AppDetectorTest(private val type: FindingType) {
    private fun detected(inputs: InsightInputs): List<Finding> = appFindings(inputs).filter {
        it.type == type && it.subject == Subject.App(UID, APP)
    }

    @Test fun positiveFlatBaselineUsesAbsoluteFloorAndStableKey() {
        val finding = detected(detectorInputs(type)).single()
        assertEquals("${type.name}:$APP", finding.key)
        assertTrue(finding.score.isFinite())
        assertTrue(finding.evidence.isNotEmpty())
        assertTrue(finding.evidence.all { it.observed.isFinite() })
        assertTrue(finding.recommendations.isEmpty())
    }

    @Test fun normalUsageDoesNotTrigger() {
        val input = detectorInputs(type)
        val normal = input.copy(appSessions = input.appSessions.map { row(it.sessionId).copy(uid = it.uid, packageName = it.packageName) })
        assertTrue(detected(normal).isEmpty())
    }

    @Test fun fewerThanFourBaselineSessionsDoNotTrigger() {
        assertTrue(detected(detectorInputs(type, baselineCount = 3)).isEmpty())
    }

    @Test fun feedbackMultiplierSuppressesTheSameFinding() {
        val input = detectorInputs(type)
        assertFalse(detected(input).isEmpty())
        assertTrue(detected(input.copy(feedback = mapOf("${type.name}:$APP" to 100.0))).isEmpty())
    }

    @Test fun unsupportedCurrentMetricsNeverBecomeZero() {
        val input = detectorInputs(type)
        val lastId = input.sessions.last().id
        assertTrue(detected(input.copy(appSessions = input.appSessions.map {
            if (it.sessionId == lastId) it.unsupported() else it
        })).isEmpty())
    }

    @Test fun unsupportedHistoryCannotSupplyTheFourthBaseline() {
        val input = detectorInputs(type)
        assertTrue(detected(input.copy(appSessions = input.appSessions.mapIndexed { index, row ->
            if (index == 0) row.unsupported() else row
        })).isEmpty())
    }

    @Test fun ineligibleWindowsNeverTrainOrDetect() {
        val input = detectorInputs(type)
        val transforms: List<(com.akane.voltwise.battery.insights.model.SessionInput) -> com.akane.voltwise.battery.insights.model.SessionInput> = listOf(
            { it.copy(imported = true) },
            { it.copy(appWindow = it.appWindow!!.copy(basis = WindowBasis.ABSOLUTE)) },
            { it.copy(appWindow = it.appWindow!!.copy(basis = WindowBasis.WINDOW_RESET)) },
            { it.copy(endMs = it.startMs + HOUR / 2, appWindow = it.appWindow!!.copy(captureEndMs = it.startMs + HOUR / 2)) },
        )
        for (transform in transforms) {
            assertTrue(detected(input.copy(sessions = input.sessions.map(transform))).isEmpty())
            assertTrue(detected(input.copy(sessions = input.sessions.mapIndexed { index, session ->
                if (index == 0) transform(session) else session
            })).isEmpty())
        }
    }

    @Test fun censoredCurrentAbsenceIsNotAnObservedSpike() {
        val input = detectorInputs(type)
        val lastId = input.sessions.last().id
        val absent = input.copy(
            sessions = input.sessions.map { it.copy(appWindow = it.appWindow!!.copy(fullRowSet = true)) },
            appSessions = input.appSessions.map {
                if (it.sessionId == lastId) it.copy(uid = 10002, packageName = "example.other") else it
            },
        )
        assertTrue(detected(absent).isEmpty())
    }

    @Test fun censoredHistoryDoesNotCountAsMeasuredBaseline() {
        val input = detectorInputs(type)
        val censored = input.copy(
            sessions = input.sessions.map { it.copy(appWindow = it.appWindow!!.copy(fullRowSet = true)) },
            appSessions = input.appSessions.mapIndexed { index, row ->
                if (index == 0) row.copy(uid = 10002, packageName = "example.other") else row
            },
        )
        // NEW_HEAVY_APP intentionally accepts below-cutoff absence as not-heavy history.
        if (type == FindingType.NEW_HEAVY_APP) assertEquals(1, detected(censored).size)
        else assertTrue(detected(censored).isEmpty())
    }

    @Test fun aggregateOthersIsNeverASubject() {
        val input = detectorInputs(type)
        assertTrue(appFindings(input.copy(appSessions = input.appSessions.map { it.copy(isOthers = true) })).isEmpty())
    }

    @Test fun confidenceReflectsMeasuredBaselineSize() {
        val finding = detected(detectorInputs(type, baselineCount = 12)).single()
        assertEquals(if (type == FindingType.NEW_HEAVY_APP) Confidence.LOW else Confidence.HIGH, finding.confidence)
    }

    @Test fun inputOrderingDoesNotChangeResults() {
        val input = detectorInputs(type)
        assertEquals(appFindings(input), appFindings(input.copy(sessions = input.sessions.reversed(), appSessions = input.appSessions.reversed())))
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<FindingType>> = listOf(
            FindingType.APP_DRAIN_ANOMALY, FindingType.NEW_HEAVY_APP, FindingType.BACKGROUND_RUNAWAY,
            FindingType.STUCK_WAKELOCK, FindingType.WAKEUP_STORM, FindingType.JOB_STORM,
            FindingType.BACKGROUND_LOCATION, FindingType.BACKGROUND_RADIO, FindingType.LINGERING_FOREGROUND_SERVICE,
        ).map { arrayOf(it) }
    }
}
