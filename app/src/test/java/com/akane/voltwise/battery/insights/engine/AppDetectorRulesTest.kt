package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.engine.detectors.app.AppContext
import com.akane.voltwise.battery.insights.engine.detectors.app.AppDrainAnomaly
import com.akane.voltwise.battery.insights.engine.detectors.app.BackgroundRunaway
import com.akane.voltwise.battery.insights.engine.detectors.app.JobStorm
import com.akane.voltwise.battery.insights.engine.detectors.app.NewHeavyApp
import com.akane.voltwise.battery.insights.engine.eligibility.AppWindows
import com.akane.voltwise.battery.insights.model.Confidence
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.InsightInputs
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.Subject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDetectorRulesTest {
    private fun context(input: InsightInputs) = AppContext(input, AppWindows.select(input), Subject.App(UID, APP))

    @Test fun runawayNeedsTwoConsecutiveObservedSessionsAndTwentyPercentBackgroundWakelock() {
        val input = detectorInputs(FindingType.BACKGROUND_RUNAWAY)
        assertEquals(1, BackgroundRunaway.detect(context(input)).size)
        val interrupted = input.copy(appSessions = input.appSessions.mapIndexed { index, row ->
            if (index == 4) row.copy(bgMs = 0, fgServiceMs = 0) else row
        })
        assertTrue(BackgroundRunaway.detect(context(interrupted)).isEmpty())
        val noHold = input.copy(appSessions = input.appSessions.mapIndexed { index, row ->
            if (index >= 4) row.copy(partialWakelockBgMs = HOUR / 5 - 1) else row
        })
        assertTrue(BackgroundRunaway.detect(context(noHold)).isEmpty())
        val boundary = input.copy(appSessions = input.appSessions.mapIndexed { index, row ->
            if (index >= 4) row.copy(partialWakelockBgMs = HOUR / 5) else row
        })
        assertEquals(1, BackgroundRunaway.detect(context(boundary)).size)
    }

    @Test fun foregroundActivityDisqualifiesLocationRadioAndLingeringService() {
        for (type in listOf(FindingType.BACKGROUND_LOCATION, FindingType.BACKGROUND_RADIO, FindingType.LINGERING_FOREGROUND_SERVICE)) {
            val input = detectorInputs(type)
            val active = input.copy(appSessions = input.appSessions.map { it.copy(fgMs = HOUR / 2, topMs = HOUR / 2) })
            assertTrue(appFindings(active).none { it.type == type })
        }
    }

    @Test fun jobStormCanUseSupportedSyncsWithoutJobs() {
        val input = detectorInputs(FindingType.JOB_STORM)
        val finding = JobStorm.detect(context(input.copy(appSessions = input.appSessions.map { it.copy(jobCount = null) }))).single()
        assertEquals(Metric.SYNCS_PER_H, finding.evidence.first().metric)
    }

    @Test fun newHeavyNeedsFifteenPercentAndBackgroundDominanceThenGraduatesToBaseline() {
        val input = detectorInputs(FindingType.NEW_HEAVY_APP)
        fun currentPower(power: Double) = input.copy(appSessions = input.appSessions.mapIndexed { index, row ->
            if (index == 4) row.copy(powerMah = power) else row
        })
        assertTrue(NewHeavyApp.detect(context(currentPower(29.9))).isEmpty())
        assertEquals(1, NewHeavyApp.detect(context(currentPower(30.0))).size)
        val foreground = input.copy(appSessions = input.appSessions.map { it.copy(bgMs = 0) })
        assertTrue(NewHeavyApp.detect(context(foreground)).isEmpty())
        val veteran = detectorInputs(FindingType.APP_DRAIN_ANOMALY)
        assertTrue(NewHeavyApp.detect(context(veteran)).isEmpty())
        assertEquals(1, AppDrainAnomaly.detect(context(veteran)).size)
    }

    @Test fun newHeavyCountsCensoredAbsenceAndPriorMeasuredButNotHeavyWindows() {
        val input = detectorInputs(FindingType.NEW_HEAVY_APP)
        val censored = input.copy(sessions = input.sessions.map { it.copy(appWindow = it.appWindow!!.copy(fullRowSet = true)) })
        assertEquals(1, NewHeavyApp.detect(context(censored)).size)
        val oneMeasured = censored.copy(appSessions = censored.appSessions.mapIndexed { index, row ->
            if (index == 0) row.copy(uid = UID, packageName = APP) else row
        })
        assertEquals(1, NewHeavyApp.detect(context(oneMeasured)).size)
        val previouslyHeavy = oneMeasured.copy(appSessions = oneMeasured.appSessions.mapIndexed { index, row ->
            if (index == 0) row.copy(powerMah = 40.0) else row
        })
        assertTrue(NewHeavyApp.detect(context(previouslyHeavy)).isEmpty())
    }

    @Test fun nonzeroMadRequiresRobustZEvenWhenAbsoluteFloorPasses() {
        val input = detectorInputs(FindingType.APP_DRAIN_ANOMALY)
        val baseline = listOf(0.0, 10.0, 20.0, 30.0)
        val noisy = input.copy(appSessions = input.appSessions.mapIndexed { index, row ->
            row.copy(powerMah = baseline.getOrNull(index) ?: 40.0)
        })
        assertTrue(AppDrainAnomaly.detect(context(noisy)).isEmpty())
        val clear = noisy.copy(appSessions = noisy.appSessions.mapIndexed { index, row -> if (index == 4) row.copy(powerMah = 100.0) else row })
        assertEquals(1, AppDrainAnomaly.detect(context(clear)).size)
    }

    @Test fun censoredHistoricalUpperBoundsReduceConfidenceAndAreNotChartMeasurements() {
        val sessions = (0..8).map { index ->
            session(index).let { it.copy(appWindow = it.appWindow!!.copy(fullRowSet = true)) }
        }
        val rows = sessions.mapIndexed { index, session ->
            when {
                index < 4 -> row(session.id)
                index < 8 -> row(session.id).copy(uid = 10002, packageName = "example.other")
                else -> highRow(session.id)
            }
        }
        val finding = AppDrainAnomaly.detect(context(inputs(sessions, rows))).single()
        assertEquals(Confidence.LOW, finding.confidence)
        assertEquals(4, finding.evidence.first().sessions)
        assertEquals(5, finding.series.size)
        val highCutoff = inputs(sessions, rows.map { if (it.uid == 10002) it.copy(powerMah = 100.0) else it })
        assertTrue(AppDrainAnomaly.detect(context(highCutoff)).isEmpty())
    }

    @Test fun latestEligibleWindowDoesNotResurfaceAnOlderSpike() {
        val input = detectorInputs(FindingType.APP_DRAIN_ANOMALY)
        val last = session(5)
        val quietNow = input.copy(sessions = input.sessions + last, appSessions = input.appSessions + row(last.id), nowMs = last.endMs + HOUR)
        assertTrue(AppDrainAnomaly.detect(context(quietNow)).isEmpty())
    }

    @Test fun findingListIsRankedCappedAndDeterministic() {
        val input = detectorInputs(FindingType.APP_DRAIN_ANOMALY)
        val many = input.copy(appSessions = (0..19).flatMap { app ->
            input.appSessions.map { it.copy(uid = UID + app, packageName = "example.app$app") }
        })
        val findings = appFindings(many)
        assertEquals(12, findings.size)
        assertEquals(findings, findings.sortedWith(compareByDescending<com.akane.voltwise.battery.insights.model.Finding> { it.score }.thenBy { it.key }))
        assertEquals(findings.size, findings.map { it.key }.distinct().size)
        assertFalse(findings.any { it.subject == Subject.Device })
    }

    @Test fun badFeedbackCannotWeakenOrPoisonThresholds() {
        val input = detectorInputs(FindingType.APP_DRAIN_ANOMALY)
        val normal = appFindings(input)
        for (multiplier in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.5)) {
            assertEquals(normal, appFindings(input.copy(feedback = mapOf("APP_DRAIN_ANOMALY:$APP" to multiplier))))
        }
    }
}
