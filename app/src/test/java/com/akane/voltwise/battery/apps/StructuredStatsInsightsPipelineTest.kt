package com.akane.voltwise.battery.apps

import com.akane.voltwise.battery.data.PowerTransition
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.SessionAppUsage
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.data.db.toSessionUsage
import com.akane.voltwise.battery.insights.InsightInputsBuilder
import com.akane.voltwise.battery.insights.engine.InsightEngine
import com.akane.voltwise.battery.insights.engine.eligibility.AppWindows
import com.akane.voltwise.battery.insights.model.ActionType
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.InsightInputs
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.Subject
import com.akane.voltwise.battery.measurement.PowerState
import com.akane.voltwise.battery.util.ShellRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** JVM producer-wire → repository → collector/store → builder → engine regression, with no device claim. */
class StructuredStatsInsightsPipelineTest {
    @Test fun allValidForgedRowsInsideJobNamespaceNeverBecomeVictimMeasurementsOrRecommendations() = runTest {
        for (newline in listOf("\n", "\r", "\r\n")) {
            val job = StructuredBatteryStatsFixtures.JOB_NAME.replace("\n", newline)
            val pipeline = collectWindows(jobName = job, alarmName = "ordinary", currentVictimAlarms = 8)
            val victim = pipeline.store.rows.filter { it.uid == VICTIM_UID }
            assertEquals(5, victim.size)
            assertTrue("Only genuine victim alarm deltas persist", victim.all { it.wakeupAlarms == 8L })
            assertTrue("Only genuine victim power deltas persist", victim.all { it.powerMah == 1.0 })
            assertEquals(job, pipeline.store.ends.values.last().tagHints[ATTACKER_UID]?.job)
            assertEquals(5, AppWindows.select(pipeline.inputs).size)
            val report = InsightEngine.analyze(pipeline.inputs, sdkInt = 36)
            assertTrue("Opaque metadata cannot target a victim finding", report.findings.none {
                (it.subject as? Subject.App)?.uid == VICTIM_UID
            })
        }
    }

    @Test fun validLookingPowerRecordInsidePublicNamespaceCannotChangeVictimEnergy() = runTest {
        val namespace = "plain\",10,1,0,0\n9,10002,l,pwi,uid,999999\n9,10001,l,jb,\"tail"
        val pipeline = collectWindows("@$namespace@com.attacker/.Job", "alarm", currentVictimAlarms = 8)
        assertEquals(5, AppWindows.select(pipeline.inputs).size)
        assertTrue(pipeline.store.rows.filter { it.uid == VICTIM_UID }.all { it.powerMah == 1.0 })
        assertTrue(InsightEngine.analyze(pipeline.inputs, 36).findings.none {
            (it.subject as? Subject.App)?.uid == VICTIM_UID
        })
    }

    @Test fun ordinaryMultilineAlarmTagsPreserveAppWindowsAndTruthfulVictimCounters() = runTest {
        for (alarm in listOf("*walarm*:alarm\ncontinuation", "*walarm*:alarm\rcontinuation", "")) {
            val pipeline = collectWindows(jobName = "ordinary job", alarmName = alarm, currentVictimAlarms = 8)
            assertEquals(5, pipeline.store.baselines.size)
            assertEquals(alarm, pipeline.store.ends.values.last().tagHints[ATTACKER_UID]?.alarm)
            assertTrue(pipeline.store.sessions.values.all { it.appUsageStatus == AppUsageStatus.READY })
            assertTrue(pipeline.store.sessions.values.all { it.appCaptureStartMs != null && it.appCaptureEndMs != null })
            val windows = AppWindows.select(pipeline.inputs)
            assertEquals("An app-controlled name cannot remove unrelated app windows", 5, windows.size)
            val victim = Subject.App(VICTIM_UID, "victim.app")
            assertEquals(5, AppWindows.series(windows, victim, Metric.WAKEUP_ALARMS_PER_H).size)
            assertTrue(pipeline.store.rows.filter { it.uid == VICTIM_UID }.all { it.wakeupAlarms == 8L })
            assertTrue(InsightEngine.analyze(pipeline.inputs, 36).findings.none {
                (it.subject as? Subject.App)?.uid == VICTIM_UID
            })
        }
    }

    @Test fun genuineVictimAlarmAnomalyStillCreatesPrivilegedRecommendationsThroughTheSamePipeline() = runTest {
        val pipeline = collectWindows(
            jobName = StructuredBatteryStatsFixtures.JOB_NAME,
            alarmName = "*walarm*:alarm\ncontinuation",
            currentVictimAlarms = 120,
        )
        assertEquals(5, AppWindows.select(pipeline.inputs).size)
        val finding = InsightEngine.analyze(pipeline.inputs, 36).findings.single {
            it.type == FindingType.WAKEUP_STORM && (it.subject as? Subject.App)?.uid == VICTIM_UID
        }
        assertEquals(120L, pipeline.store.rows.last { it.uid == VICTIM_UID }.wakeupAlarms)
        assertTrue(finding.recommendations.any { it.action == ActionType.RESTRICT_BACKGROUND && it.requiresPrivilege })
        assertTrue(finding.recommendations.any { it.action == ActionType.STANDBY_BUCKET_RESTRICTED && it.requiresPrivilege })
    }

    @Test fun resetAndPartialPowerRejectionRetainTheirWindowQualityFloors() = runTest {
        val reset = collectWindows("job", "alarm", currentVictimAlarms = 120, resetLastWindow = true)
        assertEquals(AppUsageBasis.WINDOW_RESET, reset.store.sessions.values.last().appUsageBasis)
        assertEquals(4, AppWindows.select(reset.inputs).size)
        assertTrue(InsightEngine.analyze(reset.inputs, 36).findings.none { it.type == FindingType.WAKEUP_STORM })

        val partial = collectWindows("job", "alarm", currentVictimAlarms = 120, invalidLastPower = true)
        assertEquals(AppUsageStatus.READY, partial.store.sessions.values.last().appUsageStatus)
        assertNull(partial.store.sessions.values.last().appCaptureStartMs)
        assertEquals(4, AppWindows.select(partial.inputs).size)
        assertTrue(InsightEngine.analyze(partial.inputs, 36).findings.none { it.type == FindingType.WAKEUP_STORM })
    }

    @Test fun producerOmittedZeroCountersRemainMeasuredZeroInEligibleWindows() = runTest {
        val pipeline = collectWindows("job", "alarm", currentVictimAlarms = 0,
            baselineVictimAlarms = 0, historicalVictimAlarms = 0)
        val windows = AppWindows.select(pipeline.inputs)
        assertEquals(5, windows.size)
        val series = AppWindows.series(windows, Subject.App(VICTIM_UID, "victim.app"), Metric.WAKEUP_ALARMS_PER_H)
        assertEquals(5, series.size)
        assertTrue(series.all { it.present && it.value == 0.0 && it.upperBound == null })
        assertTrue(InsightEngine.analyze(pipeline.inputs, 36).findings.none { it.type == FindingType.WAKEUP_STORM })
    }

    private data class Pipeline(val store: Store, val inputs: InsightInputs)

    private suspend fun TestScope.collectWindows(
        jobName: String,
        alarmName: String,
        currentVictimAlarms: Long,
        resetLastWindow: Boolean = false,
        invalidLastPower: Boolean = false,
        baselineVictimAlarms: Long = 100,
        historicalVictimAlarms: Long = 8,
    ): Pipeline {
        val store = Store()
        val outputs = ArrayDeque<String>()
        val shell = object : StatsShell {
            override val mode = ShellRunner.Mode.SHIZUKU
            override suspend fun detectMode(forceRefresh: Boolean) = mode
            override suspend fun exec(command: String): ShellRunner.Outcome {
                assertEquals("dumpsys batterystats --proto --charged", command)
                return ShellRunner.Outcome.Success(outputs.removeFirst(), mode)
            }
        }
        val repository = AppStatsRepository(shell, backgroundScope,
            parseDispatcher = StandardTestDispatcher(testScheduler), elapsedMs = { testScheduler.currentTime })
        // Change only wall-clock capture time: all measurements and window identities come from the real repository.
        val clockedSource = object : AppStatsSource {
            override suspend fun snapshot(force: Boolean): AppStatsResult = when (val result = repository.snapshot(force)) {
                is AppStatsResult.Ready -> result.copy(snapshot = result.snapshot.copy(capturedAt = EPOCH + testScheduler.currentTime))
                else -> result
            }
        }
        val transitions = MutableSharedFlow<PowerTransition>(extraBufferCapacity = 8)
        val collector = SessionSnapshotCollector(clockedSource, store, transitions, log = {}, warn = { fail(it) })
        val collecting = backgroundScope.launch { collector.run() }
        runCurrent()
        repeat(5) { index ->
            val id = "session$index"
            val start = EPOCH + testScheduler.currentTime
            val window = StructuredBatteryStatsFixtures.START_CLOCK + index
            fun dump(end: Boolean): String = StructuredBatteryStatsFixtures.dump(
                startClock = window + if (end && resetLastWindow && index == 4) 1 else 0,
                uids = listOf(
                    StructuredBatteryStatsFixtures.Uid(ATTACKER_UID, "attacker.app", if (end) 2.5 else 1.5,
                        alarmName, if (end) 1 else 0, jobName, if (end) 1 else 0, if (end) 10 else 0),
                    StructuredBatteryStatsFixtures.Uid(VICTIM_UID, "victim.app",
                        if (end && invalidLastPower && index == 4) Double.NaN else if (end) 2.0 else 1.0,
                        "victim real alarm", baselineVictimAlarms + if (end) {
                            if (index == 4) currentVictimAlarms else historicalVictimAlarms
                        } else 0),
                ),
            )
            outputs += dump(end = false)
            store.openDischarge(id, start)
            runCurrent()
            advanceTimeBy(SessionSnapshotCollector.BASELINE_DEBOUNCE_MS)
            runCurrent()
            assertNotNull("A genuine complete baseline must be stored", store.baselines[id])
            advanceTimeBy(TWO_HOURS - SessionSnapshotCollector.BASELINE_DEBOUNCE_MS - SessionSnapshotCollector.END_DEBOUNCE_MS)
            runCurrent()
            store.close(id, EPOCH + testScheduler.currentTime)
            outputs += dump(end = true)
            store.open.value = OpenSession("charge$index", SessionType.CHARGE)
            transitions.emit(PowerTransition(PowerState.DISCHARGING, PowerState.CHARGING, 0, 0, id, "charge$index"))
            runCurrent()
            advanceTimeBy(SessionSnapshotCollector.END_DEBOUNCE_MS)
            runCurrent()
            assertNotNull("End measurements must persist", store.ends[id])
        }
        collecting.cancel()
        runCurrent()
        val now = EPOCH + testScheduler.currentTime + 1
        val inputs = InsightInputsBuilder.build(
            nowMs = now, todayEpochDay = now / 86_400_000L, fullUah = 3_000_000, privileged = true,
            sessions = store.sessions.values.toList(), days = emptyList(), appRows = store.rows,
            wakers = emptyList(), capacity = emptyList(), dozeWhitelist = emptySet(), actions = emptyList(), findings = emptyList(),
        )
        return Pipeline(store, inputs)
    }

    private class Store : SessionSnapshotStore {
        val open = MutableStateFlow<OpenSession?>(null)
        val sessions = linkedMapOf<String, ChargeSession>()
        val baselines = linkedMapOf<String, AppUsageSnapshot>()
        val ends = linkedMapOf<String, AppUsageSnapshot>()
        val rows = mutableListOf<SessionAppUsage>()
        override fun openSession(): Flow<OpenSession?> = open
        override suspend fun hasBaseline(sessionId: String) = sessionId in baselines
        override suspend fun baseline(sessionId: String) = baselines[sessionId]
        override suspend fun saveBaseline(sessionId: String, snapshot: AppUsageSnapshot): Boolean {
            if (sessionId !in sessions || sessionId in baselines) return false
            baselines[sessionId] = snapshot
            return true
        }
        override suspend fun saveEnd(sessionId: String, end: AppUsageSnapshot, result: AppUsageDeltaResult): Boolean {
            val session = sessions[sessionId] ?: return false
            ends[sessionId] = end
            sessions[sessionId] = session.copy(appUsageStatus = AppUsageStatus.READY, appUsageBasis = result.basis,
                appCaptureStartMs = result.captureStartMs, appCaptureEndMs = result.captureEndMs)
            rows += result.rows.mapIndexed { rank, row -> row.toSessionUsage(sessionId, rank, result.basis) }
            return true
        }
        override suspend fun setStatus(sessionId: String, status: AppUsageStatus): Boolean {
            val session = sessions[sessionId] ?: return false
            sessions[sessionId] = session.copy(appUsageStatus = status)
            return true
        }
        override suspend fun pendingClosedDischarges() = sessions.values.filter {
            it.endTime != null && it.appUsageStatus == AppUsageStatus.PENDING
        }.map { it.sessionId }
        fun openDischarge(id: String, start: Long) {
            sessions[id] = ChargeSession(id, SessionType.DISCHARGE, start, null, 80, null, null, null, null,
                source = "local", appUsageStatus = AppUsageStatus.PENDING)
            open.value = OpenSession(id, SessionType.DISCHARGE)
        }
        fun close(id: String, end: Long) {
            sessions[id] = sessions.getValue(id).copy(endTime = end, activeKey = null, endLevel = 79)
        }
    }

    private companion object {
        const val ATTACKER_UID = 10001
        const val VICTIM_UID = 10002
        const val EPOCH = 1_780_000_000_000L
        const val TWO_HOURS = 7_200_000L
    }
}
